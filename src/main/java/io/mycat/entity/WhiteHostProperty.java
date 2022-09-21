package io.mycat.entity;

import lombok.Data;

import java.util.regex.Pattern;

/**
 * @title:
 * @package: io.mycat.entity
 * @description:
 * @author: HuangYW
 * @date:
 */
@Data
public class WhiteHostProperty {
    private Pattern host;
    private String user;
}
