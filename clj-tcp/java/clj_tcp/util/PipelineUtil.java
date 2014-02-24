package clj_tcp.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.Collection;

/**
 * 
 * A util to avoid type casting
 */
public class PipelineUtil {

	
	public static final void addLast(ChannelPipeline pipeline, EventExecutorGroup group, Collection<ChannelHandler> handlers){
		pipeline.addLast(group, handlers.toArray(new ChannelHandler[0]));
	}
	
	public static final void addLast(ChannelPipeline pipeline, Collection<ChannelHandler> handlers){
		pipeline.addLast(handlers.toArray(new ChannelHandler[0]));
	}
	
}
