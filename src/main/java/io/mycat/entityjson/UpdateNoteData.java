//package io.mycat.entityjson;
//
///**
// * @title:
// * @package: me.zhengjie.modules.utils
// * @description:
// * @author: HuangYW
// * @date:
// */
//
//import io.mycat.common.constant.NumConst;
//import org.apache.zookeeper.KeeperException;
//import org.apache.zookeeper.WatchedEvent;
//import org.apache.zookeeper.Watcher;
//import org.apache.zookeeper.ZooKeeper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.beans.IntrospectionException;
//import java.io.IOException;
//
//public class UpdateNoteData implements Watcher {
//    private static final Logger LOG = LoggerFactory.getLogger(UpdateNoteData.class);
//    private static ZooKeeper zooKeeper;
//
//
//    /*
//      建立会话
//     */
//
//    /**
//     * @param args args
//     * @return
//     * @throws IOException          e
//     * @throws InterruptedException e
//     */
//
//    public static void main(String[] args) throws IOException, InterruptedException {
//
//     /*
//        客户端可以通过创建一个zk实例来连接zk服务器
//        new Zookeeper(connectString,sessionTimeOut,Watcher)
//        connectString: 连接地址：IP：端口
//        sessionTimeOut：会话超时时间：单位毫秒
//        Watcher：监听器(当特定事件触发监听时，zk会通过watcher通知到客户端)
//     */
//
//        zooKeeper = new ZooKeeper("172.20.5.150:2181", NumConst.NUM_5000, new UpdateNoteData());
//        LOG.info(String.valueOf(zooKeeper.getState()) + "：正在连接至Zookeeper...");
//        Thread.sleep(Integer.MAX_VALUE);
//
//    }
//
//
//    /*
//        回调方法：处理来自服务器端的watcher通知
//     */
//
//    /**
//     * @param watchedEvent watchedEvent
//     * @return
//     */
//
//    public void process(WatchedEvent watchedEvent) {
//        // SyncConnected
//        if (watchedEvent.getState() == Event.KeeperState.SyncConnected) {
//
//            //解除主程序在CountDownLatch上的等待阻塞
//            LOG.info(String.valueOf("当前连接状态：" + zooKeeper.getState()));
//
//            // 更新数据节点内容的方法
//            try {
//                updateNoteSync();
//            } catch (KeeperException e) {
//                e.printStackTrace();
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            } catch (IntrospectionException e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//    /*
//      更新数据节点内容的方法
//     */
//    private void updateNoteSync() throws KeeperException, InterruptedException, IntrospectionException {
//
//         /*
//            path:路径
//            data:要修改的内容 byte[]
//            version:为-1，表示对最新版本的数据进行修改
//            zooKeeper.setData(path, data,version);
//         */
//
//        //获取dataHost
////        byte[] data = zooKeeper.getData("/mycat/mycat-cluster-1/schema/dataHost", false, null);
//
//        //获取firewall并解析
//        byte[] firewallData = zooKeeper.getData("/mycat/mycat-cluster-1/server/firewall", false, null);
//        String rep = new String(firewallData);
//        System.out.println(new JsonGet().parseFirewall(rep));
//
//        //修改/sys-persistent 的数据 stat: 状态信息对象
////        Stat stat = zooKeeper. ("/mycat/mycat-cluster-1/schema/dataHost", "TestFromEladmin11".getBytes(),
////                NumConst.NUM_NEGA_1);
////
////        byte[] data2 = zooKeeper.getData("/mycat/mycat-cluster-1/schema/dataHost", false, null);
////        LOG.info("修改后的值：" + new String(data2));
//
//
//        //获取dml并解析
//        byte[] dml = zooKeeper.getData("/mycat/mycat-cluster-1/server/privileges", false, null);
//        String rep1 = new String(dml);
//        System.out.println(new JsonGet().parsePrivilege(rep1));
//    }
//
//
//}
//
