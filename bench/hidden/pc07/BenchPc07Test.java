package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=pc07")
@AutoConfigureMockMvc
class BenchPc07Test {

	static final String EN = "Visit date must not be before the birth date of the pet";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private int visits(int petId) {
		return this.jdbc.queryForObject("SELECT COUNT(*) FROM visits WHERE pet_id = ?", Integer.class, petId);
	}

	@Test
	void visitBeforeBirthDateIsRejectedAndNotSaved() throws Exception {
		// pet 7 Samantha (owner 6) was born 2012-09-04 and has 2 visits
		int before = visits(7);
		String html = this.mockMvc
			.perform(post("/owners/6/pets/7/visits/new").param("date", "2012-09-03").param("description", "checkup"))
			.andExpect(status().isOk())
			.andExpect(view().name("pets/createOrUpdateVisitForm"))
			.andExpect(model().attributeHasFieldErrors("visit", "date"))
			.andExpect(model().attributeHasNoErrors("owner"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).contains(EN);
		this.mockMvc
			.perform(post("/owners/6/pets/7/visits/new").param("date", "2001-01-01").param("description", "checkup"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("visit", "date"));
		assertThat(visits(7)).isEqualTo(before);
	}

	@Test
	void visitOnOrAfterBirthDateIsBooked() throws Exception {
		int before = visits(8);
		// pet 8 Max (owner 6) was born 2012-09-04
		this.mockMvc
			.perform(
					post("/owners/6/pets/8/visits/new").param("date", "2012-09-04").param("description", "birth check"))
			.andExpect(status().is3xxRedirection())
			.andExpect(view().name("redirect:/owners/{ownerId}"));
		this.mockMvc
			.perform(
					post("/owners/6/pets/8/visits/new").param("date", "2014-05-06").param("description", "vaccination"))
			.andExpect(status().is3xxRedirection());
		assertThat(visits(8)).isEqualTo(before + 2);
	}

	@Test
	void otherVisitValidationIsUnchanged() throws Exception {
		int before = visits(1);
		// pet 1 Leo (owner 1) was born 2010-09-07
		this.mockMvc.perform(post("/owners/1/pets/1/visits/new").param("date", "2011-01-01"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("visit", "description"))
			.andExpect(model().attributeHasNoErrors("owner"));
		String html = this.mockMvc.perform(post("/owners/1/pets/1/visits/new").param("date", "2009-01-01"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("visit", "description"))
			.andExpect(model().attributeHasFieldErrors("visit", "date"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).contains(EN);
		assertThat(visits(1)).isEqualTo(before);
	}

}
