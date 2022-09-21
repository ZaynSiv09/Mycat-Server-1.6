package io.mycat.entity;

import lombok.Data;

import java.util.List;

/**
 * @title:
 * @package: io.mycat.entity
 * @description:
 * @author: HuangYW
 * @date:
 */
@Data
public class FireWallProperty {
    private List<WhiteHostProperty> whiteHost;
    private BlackListProperty blacklist;

}
