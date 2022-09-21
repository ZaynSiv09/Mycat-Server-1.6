package io.mycat.entityjson;//package io.mycat.entityjson;


import com.alibaba.fastjson.JSON;
import io.mycat.config.model.FirewallConfig;
import io.mycat.config.model.UserPrivilegesConfig;
import io.mycat.entity.DataHostProperty;
import io.mycat.entity.FireWallProperty;
import io.mycat.entity.PrivilegesProperty;

import java.beans.IntrospectionException;

/**
 * @title:
 * @package: me.zhengjie.utils
 * @description:
 * @author: HuangYW
 * @date:
 */
public class JsonGet {
    /**
     * @param jsonStr3 jsonStr3
     * @throws InterruptedException e
     * @return JSON
     * <p>
     * 解析DataHost
     */
    public DataHostProperty parseObject(String jsonStr3) throws IntrospectionException {
        DataHostProperty dataHostProperty = JSON.parseObject(jsonStr3, DataHostProperty.class);
        return new GetDataHost().dataHostParser(dataHostProperty);
    }

    /**
     * @param firewall firewall
     * @throws IntrospectionException e
     * @return
     * 解析Firewall
     */
    public FireWallProperty parseFirewall(String firewall) throws IntrospectionException {
        FireWallProperty fireWallProperty = JSON.parseObject(firewall, FireWallProperty.class);
        return new GetFireWall().firewallParser(fireWallProperty);
    }

    /**
     * @param privileges privileges
     * @throws IntrospectionException e
     * @return
     * 解析Privileges
     */

    public UserPrivilegesConfig parsePrivilege(String privileges) throws IntrospectionException {
        PrivilegesProperty privilegesProperty = JSON.parseObject(privileges, PrivilegesProperty.class);
        return new GetPrivileges().privilegesParser(privilegesProperty);
    }
}
