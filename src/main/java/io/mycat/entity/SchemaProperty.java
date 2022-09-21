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
public class SchemaProperty {
    private String name;
    private String dml;
    private List<TableProperty> table;
}
