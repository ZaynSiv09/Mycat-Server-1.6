package io.mycat.entityjson;

import io.mycat.config.model.UserPrivilegesConfig;
import io.mycat.entity.PrivilegesProperty;
import io.mycat.entity.SchemaProperty;
import io.mycat.entity.TableProperty;
import io.mycat.zkapi.DmlTrans;

import java.util.HashMap;
import java.util.Map;

/**
 * @title:
 * @package: io.mycat.entityjson
 * @description:
 * @author: HuangYW
 * @date:
 */
public class GetPrivileges {

    public UserPrivilegesConfig privilegesParser(PrivilegesProperty privilegesProperty){
        UserPrivilegesConfig userPrivilegesConfig = new UserPrivilegesConfig();
        //插入check
        userPrivilegesConfig.setCheck(privilegesProperty.getCheck());

        //获取schema
        UserPrivilegesConfig.SchemaPrivilege schemaPrivilege = new UserPrivilegesConfig.SchemaPrivilege();
        schemaPrivilege.setName(privilegesProperty.getSchema().getName());
        //schema的DML
        schemaPrivilege.setDml(DmlTrans.stringToIntArr(privilegesProperty.getSchema().getDml()));

        UserPrivilegesConfig.TablePrivilege tablePrivilege = new UserPrivilegesConfig.TablePrivilege();
        for(TableProperty tableProperty : privilegesProperty.getSchema().getTable()){
            tablePrivilege.setName(tableProperty.getName());
            tablePrivilege.setDml(DmlTrans.stringToIntArr(tableProperty.getDml()));
            //插到mycat的表级权限
            schemaPrivilege.addTablePrivilege(tableProperty.getName(),tablePrivilege);

        }
        userPrivilegesConfig.addSchemaPrivilege(schemaPrivilege.getName(),schemaPrivilege);
        return userPrivilegesConfig;
    }
}
