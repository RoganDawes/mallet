package com.sensepost.mallet.graph;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.mxgraph.analysis.StructuralException;
import com.mxgraph.analysis.mxAnalysisGraph;
import com.mxgraph.analysis.mxGraphProperties;
import com.mxgraph.analysis.mxGraphStructure;
import com.mxgraph.io.mxCodec;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxUtils;
import com.mxgraph.util.mxXmlUtils;
import com.mxgraph.view.mxGraph;
import com.sensepost.mallet.ChannelAttributes;
import com.sensepost.mallet.InterceptController;
import com.sensepost.mallet.InterceptHandler;
import com.sensepost.mallet.RelayHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;
import io.netty.util.concurrent.GlobalEventExecutor;

public class Graph implements GraphLookup {

	private boolean direct = true, socks = false;

	private mxGraph graph;

	private Map<Class<? extends Channel>, EventLoopGroup> bossGroups = new HashMap<>();
	private Map<Class<? extends Channel>, EventLoopGroup> workerGroups = new HashMap<>();
	private Map<String, Object> api = new HashMap<>();

	private ChannelGroup channels = null;
	private WeakHashMap<ChannelHandler, Object> handlerVertexMap = new WeakHashMap<>();

	public Graph(mxGraph graph, Mapping<? super String, ? extends SslContext> serverCertMapping, SslContext clientContext) {
		this.graph = graph;
		api.put("SSLServerCertificateMap", serverCertMapping);
		api.put("SSLClientContext", clientContext);
	}

	public void setInterceptController(InterceptController ic) {
		api.put("InterceptController", ic);
	}

	public mxGraph getGraph() {
		return graph;
	}

	public void loadGraph(File file) throws IOException {
		System.out.println(file.getAbsolutePath());

		Document document = mxXmlUtils.parseXml(mxUtils.readFile(file.getAbsolutePath()));

		mxCodec codec = new mxCodec(document);
		codec.decode(document.getDocumentElement(), graph.getModel());
		layoutGraph(graph);
	}

	private void layoutGraph(mxGraph graph) {
		mxIGraphLayout layout = new mxHierarchicalLayout(graph);
		graph.getModel().beginUpdate();
		try {
			Object[] cells = graph.getChildCells(graph.getDefaultParent());
			for (int i = 0; i < cells.length; i++) {
				graph.updateCellSize(cells[i]);
			}

			layout.execute(graph.getDefaultParent());
		} finally {
			graph.getModel().endUpdate();
		}
	}

	private void startServersFromGraph()
			throws StructuralException, ClassNotFoundException, IllegalAccessException, InstantiationException {
		mxAnalysisGraph aGraph = new mxAnalysisGraph();
		aGraph.setGraph(graph);

		mxGraphProperties.setDirected(aGraph.getProperties(), true);

		Object[] sourceVertices = mxGraphStructure.getSourceVertices(aGraph);
		mxIGraphModel model = aGraph.getGraph().getModel();

		channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
		for (int i = 0; i < sourceVertices.length; i++) {
			ServerBootstrap b = new ServerBootstrap().handler(new LoggingHandler(LogLevel.INFO))
					.attr(ChannelAttributes.GRAPH, this).childOption(ChannelOption.AUTO_READ, true)
					.childOption(ChannelOption.ALLOW_HALF_CLOSURE, true);
			// parse getValue() for each of sourceVertices to
			// determine what sort of EventLoopGroup we need, etc
			Object serverValue = model.getValue(sourceVertices[i]);
			Class<? extends ServerChannel> channelClass = getServerClass(getClassName(serverValue));
			b.channel(channelClass);
			SocketAddress address = parseSocketAddress(channelClass, serverValue);
			b.childHandler(new GraphChannelInitializer(graph.getOutgoingEdges(sourceVertices[i])[0]));
			b.group(getEventGroup(bossGroups, channelClass, 1), getEventGroup(workerGroups, channelClass, 0));
			channels.add(b.bind(address).syncUninterruptibly().channel());
		}
	}

	private EventLoopGroup getEventGroup(Map<Class<? extends Channel>, EventLoopGroup> cache,
			Class<? extends Channel> channelClass, int threads) {
		EventLoopGroup group = cache.get(channelClass);
		if (group != null)
			return group;
		if (channelClass == NioServerSocketChannel.class) {
			group = new NioEventLoopGroup(threads);
			cache.put(channelClass, group);
			return group;
		}
		throw new IllegalArgumentException(channelClass.toString() + " is not supported yet");
	}

	/**
	 * assumes that o is a String on two lines, first line is the class of the
	 * server, second line is the socketaddress
	 * 
	 * @param channelClass
	 * @param o
	 *            the value Object for the server vertex
	 * @return the SocketAddress specified
	 */
	private SocketAddress parseSocketAddress(Class<? extends Channel> channelClass, Object o) {
		if (NioServerSocketChannel.class.isAssignableFrom(channelClass)) {
			// parse as an InetSocketAddress
			if (o instanceof String) {
				String sa = (String) o;
				if (sa.indexOf('\n') > -1) {
					sa = sa.substring(sa.indexOf('\n') + 1);
					return parseInetSocketAddress(sa);
				}
			} else if (o instanceof Element) {
				Element e = (Element) o;
				String sa = e.getAttribute("address");
				return parseInetSocketAddress(sa);
			}
		}
		throw new IllegalArgumentException("Could not parse the socket address from: '" + o + "'");
	}

	private InetSocketAddress parseInetSocketAddress(String sa) {
		int c = sa.indexOf(':');
		if (c > -1) {
			String address = sa.substring(0, c);
			int port = Integer.parseInt(sa.substring(c + 1));
			return new InetSocketAddress(address, port);
			// FIXME: check that this is actually a bind-able
			// address?
		}
		throw new RuntimeException("Could not parse '" + sa + "' as an InetSocketAddress");
	}
	
	private Class<? extends ServerChannel> getServerClass(String className) throws ClassNotFoundException {
		Class<?> clazz = Class.forName(className);
		if (ServerChannel.class.isAssignableFrom(clazz))
			return (Class<? extends ServerChannel>) clazz;
		throw new IllegalArgumentException(className + " does not implement ServerChannel");
	}

	private boolean isSink(Object v) {
		if (v instanceof String)
			return ("Connect".equals(v));
		if (v instanceof Element) {
			Element e = (Element) v;
			return "Sink".equals(e.getTagName());
		}
		if (v instanceof GraphNode)
			return false;
		throw new RuntimeException("Unexpected cell value");
	}

	private ChannelHandler[] getChannelHandlers(Object o)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		List<ChannelHandler> handlers = new ArrayList<ChannelHandler>();
		do {
			if (graph.getModel().isEdge(o))
				o = graph.getModel().getTerminal(o, false);
			Object v = graph.getModel().getValue(o);
			if (isSink(v))
				break;
			ChannelHandler h = getChannelHandler(v);
			handlers.add(h);
			Object[] outgoing = graph.getOutgoingEdges(o);
			if (h instanceof IndeterminateChannelHandler) {
				IndeterminateChannelHandler ich = (IndeterminateChannelHandler) h;
				String[] options = new String[outgoing.length];
				for (int i = 0; i < outgoing.length; i++)
					options[i] = (String) graph.getModel().getValue(outgoing[i]);
				ich.setOutboundOptions(options);
			}
			if ((h instanceof InterceptHandler) || (h instanceof RelayHandler)
					|| (h instanceof IndeterminateChannelHandler)) {
				handlerVertexMap.put(h, o);
				break;
			}
			if (outgoing == null || outgoing.length != 1)
				break;
			o = outgoing[0];
		} while (true);
		return handlers.toArray(new ChannelHandler[handlers.size()]);
	}

	private Object getClassInstance(String description, Class<?> type, String[] arguments)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		// See if it is a defined internal object
		if (description.startsWith("{") && description.endsWith("}"))
			return api.get(description.substring(1, description.length() - 1));
		// See if it is an basic type that can easily be converted from a String
		if (type.equals(String.class)) {
			return description;
		} else if (type.equals(Integer.class) || type.equals(Integer.TYPE)) {
			return Integer.parseInt(description);
		}
		// Try to do a naive instantiation
		try {
			Class<?> clz = Class.forName(description);
			if (type.isAssignableFrom(clz)) {
				if (arguments == null || arguments.length == 0)
					return clz.newInstance();
				Constructor<?>[] constructors = clz.getConstructors();
				for (Constructor<?> c : constructors) {
					try {
						if (c.getParameterCount() == arguments.length) {
							Object[] args = new Object[arguments.length];
							Parameter[] parameters = c.getParameters();
							for (int i = 0; i < parameters.length; i++) {
								args[i] = getClassInstance(arguments[i], parameters[i].getType(), null);
							}
							return c.newInstance(args);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			} else
				throw new RuntimeException(description + " exists, but does not implement " + type.getName());
		} catch (ClassNotFoundException cnfe) {
		}
		// try to return a static field of a class
		try {
			int dot = description.lastIndexOf('.');
			String clsname = description.substring(0, dot);
			String fieldName = description.substring(dot + 1);
			Class<?> clz = Class.forName(clsname);
			Field f = clz.getField(fieldName);
			if (Modifier.isStatic(f.getModifiers())) {
				Object instance = f.get(null);
				if (instance != null && type.isAssignableFrom(instance.getClass()))
					return instance;
			}
		} catch (ClassNotFoundException cnfe) {
		} catch (NoSuchFieldException nsfe) {
		}
		throw new ClassNotFoundException("'" + description + "' not found");
	}

	private ChannelHandler getChannelHandler(Object o)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		String className = getClassName(o);
		String[] parameters = getParameters(o);
		Object handler = getClassInstance(className, ChannelHandler.class, parameters);
		return (ChannelHandler) handler;
	}

	private String getClassName(Object o) {
		if (o instanceof String) {
			String s = (String) o;
			if (s.indexOf('\n') > -1)
				s = s.substring(0, s.indexOf('\n'));
			return s;
		} else if (o instanceof Element) {
			Element e = (Element) o;
			String className = e.getAttribute("classname");
			return className;
		}
		throw new RuntimeException("Don't know how to get classname from a " + o.getClass());
	}

	private String[] getParameters(Object o) {
		if (o instanceof GraphNode) {
			return ((GraphNode) o).getArguments();
		} else if (o instanceof Element) {
			Element e = (Element) o;
			NodeList parameters = e.getElementsByTagName("Parameter");
			String[] p = new String[parameters.getLength()];
			for (int i = 0; i < parameters.getLength(); i++) {
				Node n = parameters.item(i);
				p[i] = n.getTextContent();
			}
			return p;
		}
		throw new RuntimeException("Don't know how to get parameters from a " + o.getClass());
	}

	@Override
	public void startServers() throws Exception {
		shutdownServers();
		startServersFromGraph();
	}

	@Override
	synchronized public ChannelHandler[] getNextHandlers(ChannelHandler handler, String option) {
		Object vertex = handlerVertexMap.remove(handler);
		Object[] outgoing = graph.getOutgoingEdges(vertex);
		try {
			for (Object edge : outgoing) {
				Object v = graph.getModel().getValue(edge);
				if (option.equals(v))
					return getChannelHandlers(graph.getModel().getTerminal(edge, false));
			}
			throw new NullPointerException("No match found for " + handler.getClass() + ", option " + option);
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	synchronized public ChannelHandler[] getClientChannelInitializer(ChannelHandler handler) {
		Object vertex = handlerVertexMap.remove(handler);
		try {
			Object[] outgoing = graph.getOutgoingEdges(vertex);
			if (outgoing == null || outgoing.length != 1)
				throw new IllegalStateException("Exactly one outgoing edge allowed!");
			ArrayList<ChannelHandler> handlers = new ArrayList<ChannelHandler>(
					Arrays.asList(getChannelHandlers(outgoing[0])));
			handlers.add(0, handler);
			Collections.reverse(handlers); // FIXME: Decide where to do the
											// reversing, in the graph, or in
											// the caller
			return handlers.toArray(new ChannelHandler[handlers.size()]);
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	synchronized public ChannelHandler[] getProxyInitializer(ChannelHandler handler, SocketAddress target) {
		if (direct)
			return new ChannelHandler[] { handler };
		else if (socks)
			return new ChannelHandler[] { new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", 1081)), handler };
		else
			return new ChannelHandler[] { new HttpProxyHandler(new InetSocketAddress("127.0.0.1", 8080)), handler };
	}

	@Override
	public void shutdownServers() throws Exception {
		if (channels != null)
			try {
				channels.close();
			} finally {
				shutdownEventLoop(bossGroups);
				shutdownEventLoop(workerGroups);
			}
	}

	private void shutdownEventLoop(Map<Class<? extends Channel>, EventLoopGroup> cache) {
		for (Entry<Class<? extends Channel>, EventLoopGroup> e : cache.entrySet()) {
			e.getValue().shutdownGracefully();
			cache.remove(e.getKey());
		}
	}

	private class GraphChannelInitializer extends ChannelInitializer<SocketChannel> {

		private Object serverEdge;

		public GraphChannelInitializer(Object serverEdge) {
			this.serverEdge = serverEdge;
		}

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelHandler[] handlers = getChannelHandlers(serverEdge);
			GraphLookup gl = ch.parent().attr(ChannelAttributes.GRAPH).get();
			ch.attr(ChannelAttributes.GRAPH).set(gl);
			ch.pipeline().addFirst(new ConnectionNumberChannelHandler());
			ch.pipeline().addLast(handlers);
		}

	}
}
