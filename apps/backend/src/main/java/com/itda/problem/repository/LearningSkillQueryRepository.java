package com.itda.problem.repository;

import com.itda.problem.domain.LearningSkill;
import com.itda.problem.domain.QLearningSkill;
import com.itda.problem.dto.SkillSet;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class LearningSkillQueryRepository {

    private final JPAQueryFactory queryFactory;

    public List<LearningSkill> findAllBySkillSets(
        List<SkillSet> skillSets
    ) {

        QLearningSkill qLearningSkill =
            QLearningSkill.learningSkill;

        BooleanBuilder condition =
            new BooleanBuilder();

        for (SkillSet set : skillSets) {
            condition.or(
                qLearningSkill.skillType.eq(set.skillType())
                    .and(
                        qLearningSkill.metadata.eq(
                            set.metadata()
                        )
                    )
            );
        }

        return queryFactory
            .selectFrom(qLearningSkill)
            .where(condition)
            .fetch();
    }
}
