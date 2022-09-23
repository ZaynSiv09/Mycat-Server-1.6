package io.mycat.zkapi;

import io.mycat.common.constant.NumConst;
import io.mycat.config.model.UserPrivilegesConfig;
import io.mycat.entity.FireWallProperty;
import io.mycat.entityjson.JsonGet;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import java.beans.IntrospectionException;
import java.io.IOException;

/**
 * @title:
 * @package: io.mycat.zkapi
 * @description:
 * @author: HuangYW
 * @date:
 */
public class GetZkData implements Watcher {
    private static ZooKeeper zooKeeper;

    /*
     获取zk的firewall
    */
    public FireWallProperty getNoteSync() throws KeeperException, InterruptedException, IntrospectionException {

        try {
            zooKeeper = new ZooKeeper("172.20.5.28:2181", NumConst.NUM_5000, new GetZkData());
            //获取firewall并解析
            byte[] firewallData = zooKeeper.getData("/mycat/mycat-cluster-1/server/firewall", false, null);
            String rep = new String(firewallData);
            return new JsonGet().parseFirewall(rep);

        } catch (IOException e) {
            e.printStackTrace();
        }
        Thread.sleep(Integer.MAX_VALUE);
        return null;

    }
    /*
    获取zk的dml
   */
    public UserPrivilegesConfig getPrivileges() throws KeeperException, InterruptedException, IntrospectionException {

        try {
            zooKeeper = new ZooKeeper("172.20.5.28:2181", NumConst.NUM_5000, new GetZkData());
            //获取dml并解析
            byte[] privilegesData = zooKeeper.getData("/mycat/mycat-cluster-1/server/privilege", false, null);
            String rep = new String(privilegesData);
            return new JsonGet().parsePrivilege(rep);

        } catch (IOException e) {
            e.printStackTrace();
        }
        Thread.sleep(Integer.MAX_VALUE);
        return null;

    }

    @Override
    public void process(WatchedEvent watchedEvent) {
        // SyncConnected
        if (watchedEvent.getState() == Event.KeeperState.SyncConnected) {
            //解除主程序在CountDownLatch上的等待阻塞
            System.out.println(("当前连接状态：" + zooKeeper.getState()));
        }
    }
}
