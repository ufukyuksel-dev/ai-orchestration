package org.springframework.samples.petclinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// Own context (unique property) => own in-memory database.
@SpringBootTest(properties = "bench.hidden=pc01")
@AutoConfigureMockMvc
class BenchPc01Test {

	@Autowired
	private MockMvc mockMvc;

	@SuppressWarnings("unchecked")
	private List<Owner> listPage(String lastName, int page) throws Exception {
		MvcResult result = this.mockMvc
			.perform(get("/owners").param("lastName", lastName).param("page", String.valueOf(page)))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/ownersList"))
			.andReturn();
		return (List<Owner>) result.getModelAndView().getModel().get("listOwners");
	}

	private Object model(String lastName, int page, String attribute) throws Exception {
		return this.mockMvc.perform(get("/owners").param("lastName", lastName).param("page", String.valueOf(page)))
			.andReturn()
			.getModelAndView()
			.getModel()
			.get(attribute);
	}

	@Test
	void singleMatchingOwnerWithSeveralPetsOpensTheOwnerPage() throws Exception {
		// Jean Coleman (id 6) has two pets, Samantha and Max
		this.mockMvc.perform(get("/owners").param("lastName", "Coleman"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/6"));
		// Carlos Estaban (id 10) has two pets as well
		this.mockMvc.perform(get("/owners").param("lastName", "Estaban"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/10"));
		// George Franklin (id 1) has one pet
		this.mockMvc.perform(get("/owners").param("lastName", "Franklin"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/owners/1"));
	}

	@Test
	void listingAllOwnersShowsEveryOwnerOnceAcrossPages() throws Exception {
		assertThat(((Number) model("", 1, "totalItems")).longValue()).isEqualTo(10);
		assertThat(((Number) model("", 1, "totalPages")).intValue()).isEqualTo(2);
		List<Owner> first = listPage("", 1);
		List<Owner> second = listPage("", 2);
		assertThat(first).hasSize(5);
		assertThat(second).hasSize(5);
		Set<Integer> ids = new HashSet<>();
		first.forEach(o -> ids.add(o.getId()));
		second.forEach(o -> ids.add(o.getId()));
		assertThat(ids).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

	@Test
	void prefixSearchStillListsSeveralOwners() throws Exception {
		List<Owner> davis = listPage("Davis", 1);
		assertThat(davis).extracting(Owner::getId).containsExactlyInAnyOrder(2, 4);
		assertThat(((Number) model("Davis", 1, "totalItems")).longValue()).isEqualTo(2);
		List<Owner> es = listPage("Es", 1);
		assertThat(es).extracting(Owner::getId).containsExactlyInAnyOrder(8, 10);
		this.mockMvc.perform(get("/owners").param("lastName", "Nobody"))
			.andExpect(status().isOk())
			.andExpect(view().name("owners/findOwners"));
	}

}
