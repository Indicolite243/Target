package com.stockmanager.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stockmanager.account.entity.Position;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PositionMapper extends BaseMapper<Position> {
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
