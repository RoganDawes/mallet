package com.sensepost.mallet;

import java.net.SocketAddress;

import com.sensepost.mallet.graph.GraphLookup;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

@Sharable
public class RelayHandler extends ChannelHandlerAdapter {

	private static final InternalLogger logger = InternalLoggerFactory.getInstance(RelayHandler.class);

	private boolean added = false;
	
	private Bootstrap b = new Bootstrap();
	private EventLoopGroup eventLoop = new NioEventLoopGroup();

	public RelayHandler() {
		b.group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
		.option(ChannelOption.SO_KEEPALIVE, true);
	}

	@Override
	public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
		if (!added) {
			added = true;
			setupOutboundChannel(ctx);
		}
	}

	private void setupOutboundChannel(final ChannelHandlerContext ctx) throws Exception {
		final GraphLookup gl = ctx.channel().attr(ChannelAttributes.GRAPH).get();
		final SocketAddress target = ctx.channel().attr(ChannelAttributes.TARGET).get();

		logger.info("Connecting " + ctx.channel().remoteAddress() + " -> " + target);
		final ChannelHandler[] handlers = gl.getClientChannelInitializer(this);
		ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(SocketChannel ch) throws Exception {
				ch.attr(ChannelAttributes.TARGET).set(target);
				ch.attr(ChannelAttributes.GRAPH).set(gl);
				ch.attr(ChannelAttributes.CHANNEL).set(ctx.channel());
				ctx.channel().attr(ChannelAttributes.CHANNEL).set(ch);

				ch.pipeline().addLast(handlers);
			}
		};

		b.handler(initializer).connect(target).sync();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		Channel other = ctx.attr(ChannelAttributes.CHANNEL).get();
		if (other != null && other.isOpen()) {
			other.close();
		}
		super.channelInactive(ctx);
	}

	@Override
	public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
		ChannelFuture cf = ctx.attr(ChannelAttributes.CHANNEL).get().writeAndFlush(msg);
		cf.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				if (!future.isSuccess()) {
					future.channel().close();
				}
			}
		});
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof ChannelInputShutdownEvent) {
			((SocketChannel)ctx.channel().attr(ChannelAttributes.CHANNEL).get()).shutdownOutput();
		} else
			super.userEventTriggered(ctx, evt);
	}

}
