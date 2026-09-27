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
@SpringBootTest(properties = "bench.hidden=f1t2")
@AutoConfigureMockMvc
class BenchF1T2Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private int count(String sql, Object... args) {
		return this.jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	void deletesThePetAndItsVisitsFromTheDatabase() throws Exception {
		// seed data: owner 6 has pets 7 (visits 1, 4) and 8 (visits 2, 3); 13 pets in total
		assertThat(count("SELECT COUNT(*) FROM pets")).isEqualTo(13);

		this.mockMvc.perform(post("/owners/6/pets/7/delete"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/6"));

		assertThat(count("SELECT COUNT(*) FROM pets WHERE id = 7")).as("deleted pet row").isZero();
		assertThat(count("SELECT COUNT(*) FROM pets")).as("all pet rows").isEqualTo(12);
		assertThat(count("SELECT COUNT(*) FROM pets WHERE owner_id IS NULL")).as("detached pet rows").isZero();
		assertThat(count("SELECT COUNT(*) FROM visits WHERE id IN (1, 4)")).as("visits of the deleted pet").isZero();
		assertThat(count("SELECT COUNT(*) FROM visits WHERE pet_id IS NULL")).as("detached visit rows").isZero();
		assertThat(this.jdbc.queryForList("SELECT id FROM pets WHERE owner_id = 6", Integer.class)).containsExactly(8);
		assertThat(this.jdbc.queryForList("SELECT id FROM visits ORDER BY id", Integer.class)).containsExactly(2, 3);
	}

}
