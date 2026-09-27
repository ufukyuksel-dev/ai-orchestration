package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=f2t1")
@AutoConfigureMockMvc
class BenchF2T1Test {

	private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OwnerRepository owners;

	private void owner(String firstName, String lastName, String city) {
		Owner owner = new Owner();
		owner.setFirstName(firstName);
		owner.setLastName(lastName);
		owner.setAddress("1 Test Rd.");
		owner.setCity(city);
		owner.setTelephone("6085550000");
		this.owners.save(owner);
	}

	private String html(MockHttpServletRequestBuilder request) throws Exception {
		return this.mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static int cells(String html, String text) {
		Matcher m = Pattern.compile("<td[^>]*>\\s*" + Pattern.quote(text) + "\\s*</td>").matcher(html);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
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
	void ownerSearchFiltersByCityAndKeepsFiltersWhilePaging() throws Exception {
		for (String first : new String[] { "Ann", "Bob", "Cid", "Dan", "Eve", "Fay", "Gus" }) {
			owner(first, "Testerson", "Testville");
		}
		owner("Hal", "Testerson", "Madison");

		String page1 = html(get("/owners").param("city", "Testville"));
		assertThat(cells(page1, "Testville")).as("rows on page 1").isEqualTo(5);
		assertThat(cells(page1, "Madison")).as("owners from other cities").isZero();
		assertThat(linksToPage(page1, 2)).as("links to page 2").isNotEmpty().allMatch(l -> l.contains("city=Testville"));

		String page2 = html(get("/owners").param("city", "Testville").param("page", "2"));
		assertThat(cells(page2, "Testville")).as("rows on page 2").isEqualTo(2);
		assertThat(cells(page2, "Madison")).isZero();

		String combined = html(get("/owners").param("lastName", "Testerson").param("city", "Testville"));
		assertThat(cells(combined, "Testville")).isEqualTo(5);
		assertThat(linksToPage(combined, 2)).isNotEmpty()
			.allMatch(l -> l.contains("city=Testville") && l.contains("lastName=Testerson"));

		// an empty city (as submitted by the search form) means no city filter
		String byName = html(get("/owners").param("lastName", "Testerson").param("city", ""));
		assertThat(cells(byName, "Testville") + cells(byName, "Madison")).isEqualTo(5);
		assertThat(linksToPage(byName, 2)).isNotEmpty().allMatch(l -> l.contains("lastName=Testerson"));
		String byName2 = html(get("/owners").param("lastName", "Testerson").param("page", "2"));
		assertThat(cells(byName2, "Testville") + cells(byName2, "Madison")).isEqualTo(3);

		this.mockMvc.perform(get("/owners").param("lastName", "Davis").param("city", "Windsor"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/4"));

		assertThat(html(get("/owners").param("city", "Nowhere"))).contains("id=\"search-owner-form\"");
		assertThat(html(get("/owners/find"))).contains("name=\"city\"");
	}

}
