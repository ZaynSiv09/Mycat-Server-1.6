package io.mycat.entity;

import io.mycat.config.loader.zkprocess.entity.schema.schema.Schema;
import lombok.Data;

/**
 * @title:
 * @package: io.mycat.entity
 * @description:
 * @author: HuangYW
 * @date:
 */
@Data
public class PrivilegesProperty {
    private Boolean check;
    private SchemaProperty schema;
}
