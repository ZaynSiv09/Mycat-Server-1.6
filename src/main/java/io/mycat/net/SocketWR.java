package io.mycat.net;

import org.apache.zookeeper.KeeperException;

import java.beans.IntrospectionException;
import java.io.IOException;


public abstract class SocketWR {
	public abstract void asynRead() throws IOException, IntrospectionException, InterruptedException, KeeperException;
	public abstract void doNextWriteCheck() ;
}
