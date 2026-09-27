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
@SpringBootTest(properties = "bench.hidden=h1t1")
@AutoConfigureMockMvc
class BenchH1T1Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String json(String url) throws Exception {
		MockHttpServletResponse response = this.mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn()
			.getResponse();
		assertThat(response.getContentType()).as("content type of " + url).isNotNull();
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
			.as("content type of " + url + ": " + response.getContentType())
			.isTrue();
		return response.getContentAsString();
	}

	private static Map<String, Object> petById(List<Map<String, Object>> pets, int id) {
		return pets.stream()
			.filter(p -> p.get("id") instanceof Number n && n.intValue() == id)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no pet with id " + id + " in " + pets));
	}

	@Test
	void returnsTheOwnerWithItsPetsAsJson() throws Exception {
		// seed data: owner 10 Carlos Estaban has Lucky (id 12, dog, 2010-06-24) and Sly
		// (id 13, cat, 2012-06-08)
		String body = json("/api/owners/10");

		assertThat(JsonPath.<Object>read(body, "$.id")).isInstanceOf(Number.class);
		assertThat(JsonPath.<Number>read(body, "$.id").intValue()).isEqualTo(10);
		assertThat(JsonPath.<String>read(body, "$.firstName")).isEqualTo("Carlos");
		assertThat(JsonPath.<String>read(body, "$.lastName")).isEqualTo("Estaban");
		assertThat(JsonPath.<String>read(body, "$.address")).isEqualTo("2335 Independence La.");
		assertThat(JsonPath.<String>read(body, "$.city")).isEqualTo("Waunakee");
		assertThat(JsonPath.<Object>read(body, "$.telephone")).isEqualTo("6085555487");

		List<Map<String, Object>> pets = JsonPath.read(body, "$.pets");
		assertThat(pets).hasSize(2);
		Map<String, Object> lucky = petById(pets, 12);
		assertThat(lucky.get("name")).isEqualTo("Lucky");
		assertThat(lucky.get("birthDate")).isEqualTo("2010-06-24");
		assertThat(lucky.get("type")).as("pet type name").isEqualTo("dog");
		Map<String, Object> sly = petById(pets, 13);
		assertThat(sly.get("name")).isEqualTo("Sly");
		assertThat(sly.get("birthDate")).isEqualTo("2012-06-08");
		assertThat(sly.get("type")).as("pet type name").isEqualTo("cat");

		// owner 6 Jean Coleman: Samantha (7) and Max (8), both cats born 2012-09-04
		String coleman = json("/api/owners/6");
		assertThat(JsonPath.<String>read(coleman, "$.lastName")).isEqualTo("Coleman");
		List<Map<String, Object>> colemanPets = JsonPath.read(coleman, "$.pets");
		assertThat(colemanPets).hasSize(2);
		assertThat(petById(colemanPets, 7).get("name")).isEqualTo("Samantha");
		assertThat(petById(colemanPets, 8).get("type")).isEqualTo("cat");
	}

	@Test
	void ownerWithoutPetsHasAnEmptyPetArray() throws Exception {
		this.jdbc.update("INSERT INTO owners (first_name, last_name, address, city, telephone) VALUES (?, ?, ?, ?, ?)",
				"Nora", "Petless", "1 Empty Rd.", "Nowhere", "6085550001");
		Integer id = this.jdbc.queryForObject("SELECT MAX(id) FROM owners", Integer.class);

		String body = json("/api/owners/" + id);
		assertThat(JsonPath.<Number>read(body, "$.id").intValue()).isEqualTo(id);
		assertThat(JsonPath.<String>read(body, "$.firstName")).isEqualTo("Nora");
		assertThat(JsonPath.<List<Object>>read(body, "$.pets")).isEmpty();
	}

	@Test
	void unknownOwnerIsNotFound() throws Exception {
		this.mockMvc.perform(get("/api/owners/9999")).andExpect(status().isNotFound());
	}

	@Test
	void existingHtmlPagesStillWork() throws Exception {
		String html = this.mockMvc.perform(get("/owners/10"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("Carlos Estaban", "Lucky", "Sly");
		this.mockMvc.perform(get("/owners").param("lastName", "Davis")).andExpect(status().isOk());
	}

}
