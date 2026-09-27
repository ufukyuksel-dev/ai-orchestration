package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.validation.MapBindingResult;

class BenchD1T1Test {

	private MapBindingResult validate(String name) {
		Pet pet = new Pet();
		pet.setName(name);
		pet.setBirthDate(LocalDate.of(2020, 1, 1));
		PetType type = new PetType();
		type.setName("cat");
		pet.setType(type);
		MapBindingResult errors = new MapBindingResult(new HashMap<>(), "pet");
		new PetValidator().validate(pet, errors);
		return errors;
	}

	@Test
	void rejectsNamesLongerThan30Characters() throws Exception {
		MapBindingResult errors = validate("x".repeat(31));
		assertThat(errors.getFieldError("name")).isNotNull();
		assertThat(errors.getFieldError("name").getCode()).isEqualTo("tooLong");
		assertThat(validate("x".repeat(30)).getFieldError("name")).isNull();
		Properties base = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/messages/messages.properties"))) {
			base.load(reader);
		}
		assertThat(base.getProperty("tooLong")).isNotBlank();
	}

}
