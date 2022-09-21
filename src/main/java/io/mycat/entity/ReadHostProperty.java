package io.mycat.entity;

import lombok.Data;

/**
 * @title:
 * @package: me.zhengjie.properties
 * @description:
 * @author: HuangYW
 * @date:
 */
@Data
public class ReadHostProperty {
    private String host;
    private String url;
    private String ip;
    private String port;
    private String password;
    private String user;
}
