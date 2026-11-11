package io.yuan.octopus.review.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** Additive migration for installations whose EntityManager disables schema updates. */
@Component("skillContentSchema")
public class SkillContentSchema {
    public SkillContentSchema(DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        Integer table = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_skill'", Integer.class);
        if (table == null || table == 0) return;
        for (String column : new String[]{"instructions", "references_json"}) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill' AND column_name=?", Integer.class, column);
            if (count != null && count == 0) jdbc.execute("ALTER TABLE agent_skill ADD COLUMN " + column + " LONGTEXT NULL");
        }
    }
}
