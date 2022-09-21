package io.mycat.zkapi;

/**
 * @title:
 * @package: io.mycat.zkapi
 * @description:
 * @author: HuangYW
 * @date:
 */
public class DmlTrans {
    public static int[] stringToIntArr(String str) {
        int[] intArr = new int[str.length()];
        char[] ch = str.toCharArray();
        for (int i = 0; i < str.length(); i++) {
            intArr[i] = (int) ch[i] - 48;
        }

        return intArr;
    }
}
