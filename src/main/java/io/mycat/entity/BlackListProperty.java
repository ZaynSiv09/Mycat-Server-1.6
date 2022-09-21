package io.mycat.entity;

import io.mycat.config.loader.zkprocess.entity.Property;
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
public class BlackListProperty {
    private Boolean check;
    private List<Property> property;
}
