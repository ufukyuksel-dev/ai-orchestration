package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=pc02")
@AutoConfigureMockMvc
class BenchPc02Test {

	private static final String EN = "Telephone must be a 10-digit number";

	private static final String DE = "Telefonnummer muss aus 10 Ziffern bestehen";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String createOwner(MockHttpSession session, String telephone) throws Exception {
		return this.mockMvc
			.perform(post("/owners/new").session(session)
				.param("firstName", "Nora")
				.param("lastName", "Phoneless")
				.param("address", "1 Main St.")
				.param("city", "Madison")
				.param("telephone", telephone))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("owner", "telephone"))
			.andExpect(view().name("owners/createOrUpdateOwnerForm"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	@Test
	void invalidTelephoneOnNewOwnerShowsTheProjectMessage() throws Exception {
		int before = this.jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class);
		String html = createOwner(new MockHttpSession(), "12345");
		assertThat(html).contains(EN).doesNotContain("{telephone.invalid}").doesNotContain("must match");
		html = createOwner(new MockHttpSession(), "608555102x");
		assertThat(html).contains(EN).doesNotContain("{telephone.invalid}");
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class)).isEqualTo(before);
	}

	@Test
	void invalidTelephoneOnOwnerEditShowsTheProjectMessage() throws Exception {
		String html = this.mockMvc
			.perform(post("/owners/1/edit").param("firstName", "George")
				.param("lastName", "Franklin")
				.param("address", "110 W. Liberty St.")
				.param("city", "Madison")
				.param("telephone", "608555102311"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("owner", "telephone"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains(EN).doesNotContain("{telephone.invalid}");
		assertThat(this.jdbc.queryForObject("SELECT telephone FROM owners WHERE id = 1", String.class))
			.isEqualTo("6085551023");
	}

	@Test
	void messageFollowsTheSelectedLanguage() throws Exception {
		MockHttpSession session = new MockHttpSession();
		this.mockMvc.perform(get("/").param("lang", "de").session(session)).andExpect(status().isOk());
		String html = createOwner(session, "12345");
		assertThat(html).contains(DE).doesNotContain(EN).doesNotContain("{telephone.invalid}");
	}

	@Test
	void otherValidationMessagesAndValidOwnersAreUnchanged() throws Exception {
		String html = this.mockMvc
			.perform(post("/owners/new").param("firstName", "Nora")
				.param("lastName", "Phoneless")
				.param("city", "Madison")
				.param("telephone", "6085550000"))
			.andExpect(status().isOk())
			.andExpect(model().attributeHasFieldErrors("owner", "address"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("must not be blank");
		this.mockMvc
			.perform(post("/owners/new").param("firstName", "Nora")
				.param("lastName", "Phoneful")
				.param("address", "1 Main St.")
				.param("city", "Madison")
				.param("telephone", "6085550000"))
			.andExpect(status().is3xxRedirection());
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM owners WHERE last_name = 'Phoneful'", Integer.class))
			.isEqualTo(1);
	}

}
