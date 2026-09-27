package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database; requests commit like in production.
@SpringBootTest(properties = "bench.hidden=f1t3")
@AutoConfigureMockMvc
class BenchF1T3Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void purgesOnlyTheOlderVisitsOfThatPet() throws Exception {
		// seed data: owner 6 has pets 7 (visits 1 @2013-01-01, 4 @2013-01-04)
		// and 8 (visits 2 @2013-01-02, 3 @2013-01-03)
		assertThat(count("SELECT COUNT(*) FROM visits")).isEqualTo(4);

		this.mockMvc.perform(post("/owners/6/pets/8/visits/purge").param("before", "2013-01-03"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/6"));

		assertThat(count("SELECT COUNT(*) FROM visits WHERE id = 2")).as("purged visit row").isZero();
		assertThat(count("SELECT COUNT(*) FROM visits")).as("all visit rows").isEqualTo(3);
		assertThat(count("SELECT COUNT(*) FROM visits WHERE pet_id IS NULL")).as("detached visit rows").isZero();
		assertThat(this.jdbc.queryForList("SELECT id FROM visits WHERE pet_id = 8", Integer.class)).containsExactly(3);
		assertThat(this.jdbc.queryForList("SELECT id FROM visits WHERE pet_id = 7 ORDER BY id", Integer.class))
			.containsExactly(1, 4);
	}

}
