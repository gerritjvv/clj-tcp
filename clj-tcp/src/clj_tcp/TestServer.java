package clj_tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class TestServer {

	
	public static void main(String args[]) throws Throwable{
		 EventLoopGroup bossGroup = new NioEventLoopGroup(); // (1)
	        EventLoopGroup workerGroup = new NioEventLoopGroup();
	        try {
	            ServerBootstrap b = new ServerBootstrap(); // (2)
	            b.group(bossGroup, workerGroup)
	             .channel(NioServerSocketChannel.class) // (3)
	             .childHandler(new ChannelInitializer<SocketChannel>() { // (4)
	                 @Override
	                 public void initChannel(SocketChannel ch) throws Exception {
	                	 ch.pipeline().addLast(new ChannelOutboundHandlerAdapter(){

							@Override
							public void write(ChannelHandlerContext ctx,
									Object msg, ChannelPromise promise)
									throws Exception {
								System.out.println("outbound: " + new String((byte[])msg));
								super.write(ctx, msg, promise);
							}

							@Override
							public void exceptionCaught(
									ChannelHandlerContext ctx, Throwable cause)
									throws Exception {
								System.out.println("ERROR outbound: " + cause);
								super.exceptionCaught(ctx, cause);
							}
	                		 
	                	 });
	                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){
	                    	 
							@Override
							public void channelRegistered(
									ChannelHandlerContext ctx) throws Exception {
								// TODO Auto-generated method stub
								System.out.println("Registered");
								super.channelRegistered(ctx);
							}

							@Override
							public void channelUnregistered(
									ChannelHandlerContext ctx) throws Exception {
								// TODO Auto-generated method stub
								System.out.println("UnRegistered");
								super.channelUnregistered(ctx);
							}

							@Override
							public void channelInactive(
									ChannelHandlerContext ctx) throws Exception {
								// TODO Auto-generated method stub
								System.out.println("Inactive");
								super.channelInactive(ctx);
							}

							@Override
							public void channelReadComplete(
									ChannelHandlerContext ctx) throws Exception {
								// TODO Auto-generated method stub
								System.out.println("Readcomplete");
								super.channelReadComplete(ctx);
							}

							@Override
							public void channelWritabilityChanged(
									ChannelHandlerContext ctx) throws Exception {
								// TODO Auto-generated method stub
								System.out.println("Writable");
								super.channelWritabilityChanged(ctx);
							}

							@Override
							public void exceptionCaught(
									ChannelHandlerContext ctx, Throwable cause)
									throws Exception {
								System.out.println("Exception " + cause);
								super.exceptionCaught(ctx, cause);
							}

							@Override
							public void userEventTriggered(
									ChannelHandlerContext ctx, Object evt)
									throws Exception {
								
								System.out.println("Event: " + evt);
								super.userEventTriggered(ctx, evt);
							}

							@Override
							public void channelRead(ChannelHandlerContext ctx,
									Object msg) throws Exception {
								System.out.println("received and sending response");
								ctx.writeAndFlush("HI".getBytes());
								
								//.addListener(ChannelFutureListener.CLOSE);
								
							}
	                    	 
	                     });
	                 }
	             })
	             .option(ChannelOption.SO_BACKLOG, 128)          // (5)
	             .childOption(ChannelOption.SO_KEEPALIVE, true); // (6)
	    
	            // Bind and start to accept incoming connections.
	            ChannelFuture f = b.bind(2326).sync(); // (7)
	            System.out.println("Connected");
	            // Wait until the server socket is closed.
	            // In this example, this does not happen, but you can do that to gracefully
	            // shut down your server.
	            System.out.println("Waiting");
	            f.channel().closeFuture().sync();
	        } finally {
	            workerGroup.shutdownGracefully();
	            bossGroup.shutdownGracefully();
	        }
		
	}
}
