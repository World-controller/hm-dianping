package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)// 开启链式调用，例如：对象.setA().setB()
@TableName("tb_seckill_voucher")// MyBatis-Plus注解，指定对应的数据库表名
public class SeckillVoucher implements Serializable {

    private static final long serialVersionUID = 1L;// 序列化版本号，用于版本控制

    /**
     * 关联的优惠券的id
     */
    @TableId(value = "voucher_id", type = IdType.INPUT)//MyBatis-Plus的注解，标识这是数据库表的主键字段
    // value = "voucher_id": 指定数据库表中对应的列名是"voucher_id"
//    type = IdType.INPUT: 指定主键的生成策略是手动输入
    private Long voucherId;

    /**
     * 库存
     */
    private Integer stock;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 生效时间
     */
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
