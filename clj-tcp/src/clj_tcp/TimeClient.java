package clj_tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class TimeClient {
	 public static void main(String[] args) throws Exception {
	        String host = "localhost";
	        int port = 2326;
	        EventLoopGroup workerGroup = new NioEventLoopGroup();
	        System.out.println("Client ");
	        try {
	            Bootstrap b = new Bootstrap(); // (1)
	            b.group(workerGroup); // (2)
	            b.channel(NioSocketChannel.class); // (3)
	            b.option(ChannelOption.AUTO_READ, true);
	            b.option(ChannelOption.SO_KEEPALIVE, true); // (4)
	            b.handler(new ChannelInitializer<SocketChannel>() {
	                @Override
	                public void initChannel(SocketChannel ch) throws Exception {
	                    ch.pipeline().addLast(new TimeClientHandler())
	                    .addLast(new StringEncoder())
	                    .addLast(new StringDecoder());
	                    System.out.println("Adding client handler");
	                }
	            });
	            
	            // Start the client.
	            ChannelFuture f = b.connect(host, port).sync(); // (5)
	            f.channel().config().setAutoRead(true);
	            f.channel().writeAndFlush("hi").sync();
	            f.channel().read();
	            f.channel().read();
	            f.channel().read();
		            
	            
	            // Wait until the connection is closed.
	           // f.channel().closeFuture().sync();
	        } finally {
	            workerGroup.shutdownGracefully();
	        }
	    }
}
