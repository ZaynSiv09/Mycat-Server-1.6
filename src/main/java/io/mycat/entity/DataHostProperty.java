package io.mycat.entity;

import lombok.Data;

import java.util.List;

/**
 * @title:
 * @package: me.zhengjie.properties
 * @description:
 * @author: HuangYW
 * @date:
 */
@Data
public class DataHostProperty {
    private Integer balance;
    private Integer maxCon;
    private Integer minCon;
    private String name;
    private Integer writeType;
    private Integer switchType;
    private Integer slaveThreshold;
    private String dbType;
    private String dbDriver;
    private String heartbeat;
    private List<WriteHostProperty> writeHost;

}
