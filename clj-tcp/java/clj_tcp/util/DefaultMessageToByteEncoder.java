package clj_tcp.util;

import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBuf;
import clojure.lang.IFn;
import java.util.concurrent.RejectedExecutionException;

/**
 *
 */
public class DefaultMessageToByteEncoder extends MessageToByteEncoder{

    IFn loggerFn;

    public DefaultMessageToByteEncoder(IFn loggerFn, boolean preferDirect){
        super(preferDirect);
        this.loggerFn = loggerFn;
    }


    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause){
        if(!(cause instanceof RejectedExecutionException))
            loggerFn.invoke(cause);
    }

    public void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception{
        if(msg instanceof IFn)
            ((IFn)msg).invoke(out);
        else if(msg instanceof String)
            out.writeBytes(((String)msg).getBytes("UTF-8"));
        else if(msg instanceof ByteBuf)
            out.writeBytes((ByteBuf)msg);
        else
            out.writeBytes((byte[])msg);
    }

}
