package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=h1t3")
@AutoConfigureMockMvc
class BenchH1T3Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private List<Map<String, Object>> visits(String url) throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(get(url))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).as("content type of " + url).isNotNull();
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
			.as("content type of " + url + ": " + response.getContentType())
			.isTrue();
		String body = response.getContentAsString();
		assertThat(JsonPath.<Object>read(body, "$")).as("top-level JSON array").isInstanceOf(List.class);
		return JsonPath.read(body, "$");
	}

	private static List<Object> field(List<Map<String, Object>> visits, String name) {
		return visits.stream().map(v -> v.get(name)).map(o -> o instanceof Number n ? (Object) n.intValue() : o).toList();
	}

	@Test
	void returnsThePetsVisitsSortedByDate() throws Exception {
		// seed data: pet 7 (Samantha, owner 6) has visits 1 (2013-01-01 rabies shot) and
		// 4 (2013-01-04 spayed); a later-inserted but earlier-dated visit must come first
		this.jdbc.update("INSERT INTO visits (pet_id, visit_date, description) VALUES (?, ?, ?)", 7,
				java.sql.Date.valueOf("2012-12-25"), "christmas checkup");
		Integer extra = this.jdbc.queryForObject("SELECT MAX(id) FROM visits", Integer.class);

		List<Map<String, Object>> samantha = visits("/api/owners/6/pets/7/visits");
		assertThat(field(samantha, "id")).containsExactly(extra, 1, 4);
		assertThat(field(samantha, "date")).containsExactly("2012-12-25", "2013-01-01", "2013-01-04");
		assertThat(field(samantha, "description")).containsExactly("christmas checkup", "rabies shot", "spayed");

		// pet 8 (Max, owner 6): visits 2 (2013-01-02 rabies shot), 3 (2013-01-03 neutered)
		List<Map<String, Object>> max = visits("/api/owners/6/pets/8/visits");
		assertThat(field(max, "id")).containsExactly(2, 3);
		assertThat(field(max, "date")).containsExactly("2013-01-02", "2013-01-03");
		assertThat(field(max, "description")).containsExactly("rabies shot", "neutered");

		// repeated reads must not change the result (no phantom visits)
		assertThat(visits("/api/owners/6/pets/7/visits")).hasSize(3);
		assertThat(this.jdbc.queryForObject("SELECT COUNT(*) FROM visits", Integer.class)).isEqualTo(5);
	}

	@Test
	void petWithoutVisitsGivesAnEmptyArray() throws Exception {
		// pet 1 (Leo, owner 1) has no visits
		assertThat(visits("/api/owners/1/pets/1/visits")).isEmpty();
	}

	@Test
	void notFoundWhenOwnerOrPetDoesNotMatch() throws Exception {
		// pet 7 belongs to owner 6, not owner 1
		this.mockMvc.perform(get("/api/owners/1/pets/7/visits")).andExpect(status().isNotFound());
		this.mockMvc.perform(get("/api/owners/9999/pets/7/visits")).andExpect(status().isNotFound());
		this.mockMvc.perform(get("/api/owners/6/pets/9999/visits")).andExpect(status().isNotFound());
	}

	@Test
	void existingHtmlPagesStillWork() throws Exception {
		String html = this.mockMvc.perform(get("/owners/6"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("Samantha", "rabies shot");
		this.mockMvc.perform(get("/owners/6/pets/7/visits/new")).andExpect(status().isOk());
	}

}
