package com.example.ekb.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ekb.knowledge.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Select("""
            SELECT *
            FROM knowledge_base
            WHERE id = #{knowledgeBaseId}
              AND owner_user_id = #{currentUserId}
              AND is_deleted = 0
            FOR UPDATE
            """)
    KnowledgeBase selectOwnedForUpdate(
            @Param("currentUserId") Long currentUserId,
            @Param("knowledgeBaseId") Long knowledgeBaseId
    );
}
