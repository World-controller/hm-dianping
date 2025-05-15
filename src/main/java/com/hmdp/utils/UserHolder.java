package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/*
*   1.每个线程都有自己的ThreadLocalMap
    2.ThreadLocalMap是一个定制的哈希表，用于存储该线程的所有ThreadLocal变量
    3.ThreadLocal对象本身作为key，存储的用户数据(UserDTO)作为value
    4.同一个ThreadLocal实例(如UserHolder.tl)在不同线程中对应不同的值
    5.当调用UserHolder.saveUser(userDTO)时，实际上是将userDTO存储到当前线程的ThreadLocalMap中
    6.当调用UserHolder.getUser()时，是从当前线程的ThreadLocalMap中获取对应的值
* */

/*如何实现线程隔离？
在ThreadLocal的源码中，无论是它的put方法还是get方法， 都是先从获得当前用户的线程，然后从线程中取出线程的成员变量map，只要线程不一样，map就不一样，所以可以通过这种方式来做到线程隔离。
* */

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
