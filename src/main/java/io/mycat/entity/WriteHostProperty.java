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
public class WriteHostProperty {
    private String host;
    private String url;
    private String ip;
    private String port;
    private String password;
    private String user;
    private List<ReadHostProperty> readHost;
}
