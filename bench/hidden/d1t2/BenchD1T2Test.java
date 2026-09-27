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

class BenchD1T2Test {

	private MapBindingResult validate(LocalDate birthDate) {
		Pet pet = new Pet();
		pet.setName("Leo");
		pet.setBirthDate(birthDate);
		PetType type = new PetType();
		type.setName("cat");
		pet.setType(type);
		MapBindingResult errors = new MapBindingResult(new HashMap<>(), "pet");
		new PetValidator().validate(pet, errors);
		return errors;
	}

	@Test
	void rejectsBirthDatesInTheFuture() throws Exception {
		MapBindingResult errors = validate(LocalDate.now().plusDays(3));
		assertThat(errors.getFieldError("birthDate")).isNotNull();
		assertThat(errors.getFieldError("birthDate").getCode()).isEqualTo("futureDate");
		assertThat(validate(LocalDate.now().minusDays(3)).getFieldError("birthDate")).isNull();
		Properties base = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/messages/messages.properties"))) {
			base.load(reader);
		}
		assertThat(base.getProperty("futureDate")).isNotBlank();
	}

}
