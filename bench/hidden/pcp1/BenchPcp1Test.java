package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=pcp1")
@AutoConfigureMockMvc
class BenchPcp1Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private int pets(int ownerId) {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM pets WHERE owner_id = ?", Integer.class, ownerId);
	}

	@Test
	void duplicateNameInOtherLetterCaseIsRejected() throws Exception {
		// owner 1 George Franklin already has Leo
		int before = pets(1);
		for (String name : new String[] { "Leo", "LEO", "leo" }) {
			this.mockMvc
				.perform(post("/owners/1/pets/new").param("name", name)
					.param("type", "cat")
					.param("birthDate", "2015-02-12"))
				.andExpect(status().isOk())
				.andExpect(model().attributeHasFieldErrorCode("pet", "name", "duplicate"));
		}
		assertThat(pets(1)).isEqualTo(before);
	}

	@Test
	void differentNameIsAccepted() throws Exception {
		int before = pets(2);
		this.mockMvc
			.perform(post("/owners/2/pets/new").param("name", "Basilisk")
				.param("type", "lizard")
				.param("birthDate", "2015-02-12"))
			.andExpect(status().is3xxRedirection());
		assertThat(pets(2)).isEqualTo(before + 1);
	}

}
