package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.Position;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** position 当前持仓表的数据访问接口，额外提供单 SQL 批量插入能力。 */
@Mapper
public interface PositionMapper extends BaseMapper<Position> {
    /**
     * 批量写入一次完整持仓集合，避免逐条 INSERT 造成大量数据库往返。
     * 调用方必须确保集合非空并已为每行生成主键。
     */
    @Insert("""
            <script>
            INSERT INTO position (id, account_id, security_code, security_name, quantity, available_quantity,
                                  cost_price, last_price, market_value, profit_loss, industry, region)
            VALUES
            <foreach collection="positions" item="p" separator=",">
              (#{p.id}, #{p.accountId}, #{p.securityCode}, #{p.securityName}, #{p.quantity}, #{p.availableQuantity},
               #{p.costPrice}, #{p.lastPrice}, #{p.marketValue}, #{p.profitLoss}, #{p.industry}, #{p.region})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("positions") List<Position> positions);
}
