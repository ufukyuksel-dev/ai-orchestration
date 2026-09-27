package org.springframework.samples.petclinic.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class BenchD2T1Test {

	@Test
	void ownerHasAnOptionalEmailThatIsStoredAndShown() throws Exception {
		Owner owner = new Owner();
		Owner.class.getMethod("setEmail", String.class).invoke(owner, "a@b.c");
		assertThat(Owner.class.getMethod("getEmail").invoke(owner)).isEqualTo("a@b.c");
		for (String db : new String[] { "h2", "mysql", "postgres" }) {
			assertThat(Files.readString(Path.of("src/main/resources/db/" + db + "/schema.sql")).toLowerCase())
				.as(db + " schema").contains("email");
		}
		assertThat(Files.readString(Path.of("src/main/resources/templates/owners/createOrUpdateOwnerForm.html")))
			.contains("email");
		assertThat(Files.readString(Path.of("src/main/resources/templates/owners/ownerDetails.html"))).contains("email");
	}

}
