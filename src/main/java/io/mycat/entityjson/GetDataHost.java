package io.mycat.entityjson;

import io.mycat.common.constant.NumConst;
import io.mycat.entity.DataHostProperty;
import io.mycat.entity.ReadHostProperty;
import io.mycat.entity.WriteHostProperty;

import java.beans.IntrospectionException;

/**
 * @title:
 * @package: me.zhengjie.utils
 * @description:
 * @author: HuangYW
 * @date:
 */
public class GetDataHost {

    /**
     * @param  dataHostProperty dataHostProperties
     * @throws IntrospectionException e
     * @return dataHostProperties
     *
     */

    public DataHostProperty dataHostParser(DataHostProperty dataHostProperty) throws IntrospectionException {
        //解析一下ip和port
        for (WriteHostProperty writeHostProperty : dataHostProperty.getWriteHost()) {
            Integer urlLength = writeHostProperty.getUrl().length();  //27
            String urlBase = writeHostProperty.getUrl().substring(NumConst.NUM_13, urlLength); //localhost:3306
            String port = urlBase.substring(urlBase.length() - NumConst.NUM_4, urlBase.length());
            String ip = urlBase.substring(0, urlBase.length() - NumConst.NUM_5);
            writeHostProperty.setIp(ip);
            writeHostProperty.setPort(port);
            //如果readHost有内容
            if (!ReflectUtil.isNullObject(writeHostProperty.getReadHost())) {
                for (ReadHostProperty readHostProperty : writeHostProperty.getReadHost()) {
                    Integer urlLengthR = readHostProperty.getUrl().length();  //27
                    String urlBaseR = readHostProperty.getUrl().substring(NumConst.NUM_13, urlLengthR);
                    String portR = urlBaseR.substring(urlBaseR.length() - NumConst.NUM_4, urlBaseR.length());
                    String ipR = urlBaseR.substring(0, urlBaseR.length() - NumConst.NUM_5);
                    readHostProperty.setIp(ipR);
                    readHostProperty.setPort(portR);
                }
            }
        }
        return dataHostProperty;
    }

}

