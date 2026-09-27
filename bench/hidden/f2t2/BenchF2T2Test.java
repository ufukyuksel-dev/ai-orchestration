package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Own context (unique property) => own in-memory database and own caches.
@SpringBootTest(properties = "bench.hidden=f2t2")
@AutoConfigureMockMvc
class BenchF2T2Test {

	private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String html(MockHttpServletRequestBuilder request) throws Exception {
		return this.mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static int count(String html, String regex) {
		Matcher m = Pattern.compile(regex).matcher(html);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	private static int rows(String html) {
		// vet rows: "<td>First Last</td>" name cells inside the vets table body
		String body = html.substring(html.indexOf("<tbody"), html.indexOf("</tbody>"));
		return count(body, "<tr");
	}

	private static List<String> linksToPage(String html, int page) {
		List<String> links = new ArrayList<>();
		Matcher m = HREF.matcher(html);
		while (m.find()) {
			String href = m.group(1).replace("&amp;", "&");
			if (href.matches(".*[?&]page=" + page + "(&.*)?$")) {
				links.add(href);
			}
		}
		return links;
	}

	@Test
	void vetListFiltersBySpecialtyAndKeepsTheFilterWhilePaging() throws Exception {
		// seed data: radiology (id 1) = Helen Leary, Henry Stevens; surgery-only = Rafael
		// Ortega; no specialty = James Carter, Sharon Jenkins
		for (int i = 1; i <= 6; i++) {
			this.jdbc.update("INSERT INTO vets (first_name, last_name) VALUES (?, ?)", "Ray" + i, "Testvet");
			Integer id = this.jdbc.queryForObject("SELECT MAX(id) FROM vets", Integer.class);
			this.jdbc.update("INSERT INTO vet_specialties VALUES (?, 1)", id);
		}

		String page1 = html(get("/vets.html").param("specialty", "radiology"));
		String page2 = html(get("/vets.html").param("specialty", "radiology").param("page", "2"));
		assertThat(rows(page1)).as("rows on page 1").isEqualTo(5);
		assertThat(rows(page2)).as("rows on page 2").isEqualTo(3);
		String both = page1 + page2;
		assertThat(both).contains("Leary", "Stevens");
		assertThat(count(both, "Testvet")).isEqualTo(6);
		assertThat(both).doesNotContain("Carter", "Ortega", "Jenkins", "Douglas");
		assertThat(linksToPage(page1, 2)).as("links to page 2")
			.isNotEmpty()
			.allMatch(l -> l.contains("specialty=radiology"));
		assertThat(linksToPage(page2, 1)).as("links to page 1")
			.isNotEmpty()
			.allMatch(l -> l.contains("specialty=radiology"));

		String surgery = html(get("/vets.html").param("specialty", "surgery"));
		assertThat(rows(surgery)).isEqualTo(2);
		assertThat(surgery).contains("Douglas", "Ortega").doesNotContain("Leary");

		// no (or an empty) specialty means no filter: 12 vets, 3 pages
		String all = html(get("/vets.html").param("page", "3"));
		assertThat(rows(all)).isEqualTo(2);
		String allEmpty = html(get("/vets.html").param("specialty", ""));
		assertThat(rows(allEmpty)).isEqualTo(5);
		assertThat(linksToPage(allEmpty, 3)).isNotEmpty();
	}

}
