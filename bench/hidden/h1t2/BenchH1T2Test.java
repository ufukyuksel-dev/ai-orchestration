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

// Own context (unique property) => own in-memory database and own caches.
@SpringBootTest(properties = "bench.hidden=h1t2")
@AutoConfigureMockMvc
class BenchH1T2Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private static Map<String, Object> vetById(List<Map<String, Object>> vets, int id) {
		return vets.stream()
			.filter(v -> v.get("id") instanceof Number n && n.intValue() == id)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no vet with id " + id + " in " + vets));
	}

	@Test
	void returnsAllVetsWithSortedSpecialtyNames() throws Exception {
		// extra vet whose specialties are stored out of alphabetical order (surgery,
		// radiology). Inserted before the first request, so no cache is stale.
		this.jdbc.update("INSERT INTO vets (first_name, last_name) VALUES (?, ?)", "Mia", "Multi");
		Integer mia = this.jdbc.queryForObject("SELECT MAX(id) FROM vets", Integer.class);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 2)", mia);
		this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 1)", mia);

		MockHttpServletResponse response = this.mockMvc.perform(get("/api/vets"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).isNotNull();
		assertThat(MediaType.parseMediaType(response.getContentType()).isCompatibleWith(MediaType.APPLICATION_JSON))
			.as("content type " + response.getContentType())
			.isTrue();
		String body = response.getContentAsString();

		assertThat(JsonPath.<Object>read(body, "$")).as("top-level JSON array").isInstanceOf(List.class);
		List<Map<String, Object>> vets = JsonPath.read(body, "$");
		assertThat(vets).hasSize(7);

		// seed data: 1 James Carter (none), 2 Helen Leary (radiology), 3 Linda Douglas
		// (surgery, dentistry), 4 Rafael Ortega (surgery), 5 Henry Stevens (radiology),
		// 6 Sharon Jenkins (none)
		Map<String, Object> carter = vetById(vets, 1);
		assertThat(carter.get("firstName")).isEqualTo("James");
		assertThat(carter.get("lastName")).isEqualTo("Carter");
		assertThat(carter.get("specialties")).as("no specialties => empty array").isEqualTo(List.of());

		Map<String, Object> leary = vetById(vets, 2);
		assertThat(leary.get("firstName")).isEqualTo("Helen");
		assertThat(leary.get("lastName")).isEqualTo("Leary");
		assertThat(leary.get("specialties")).isEqualTo(List.of("radiology"));

		Map<String, Object> douglas = vetById(vets, 3);
		assertThat(douglas.get("lastName")).isEqualTo("Douglas");
		assertThat(douglas.get("specialties")).isEqualTo(List.of("dentistry", "surgery"));

		assertThat(vetById(vets, 4).get("specialties")).isEqualTo(List.of("surgery"));
		assertThat(vetById(vets, 5).get("specialties")).isEqualTo(List.of("radiology"));
		assertThat(vetById(vets, 6).get("lastName")).isEqualTo("Jenkins");
		assertThat(vetById(vets, 6).get("specialties")).isEqualTo(List.of());

		Map<String, Object> multi = vetById(vets, mia);
		assertThat(multi.get("firstName")).isEqualTo("Mia");
		assertThat(multi.get("lastName")).isEqualTo("Multi");
		assertThat(multi.get("specialties")).isEqualTo(List.of("radiology", "surgery"));

		existingVetPagesStillWork();
	}

	// Called after the data is inserted: the first findAll() fills the "vets" cache.
	private void existingVetPagesStillWork() throws Exception {
		String html = this.mockMvc.perform(get("/vets.html"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(html).contains("Carter", "Leary");
		String resource = this.mockMvc.perform(get("/vets").accept(MediaType.APPLICATION_JSON))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<Object>>read(resource, "$.vetList")).isNotEmpty();
	}

}
