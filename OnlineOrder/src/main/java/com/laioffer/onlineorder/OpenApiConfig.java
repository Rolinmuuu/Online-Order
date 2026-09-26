package com.laioffer.onlineorder;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** The API description at /actuator/openapi (management port); docs/openapi.json is its committed copy. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI onlineOrderApi() {
        // /login and /logout are implemented by Spring Security's filters, so springdoc cannot see them.
        Schema<?> credentials = new ObjectSchema()
                .addProperty("username", new StringSchema())
                .addProperty("password", new StringSchema().format("password"))
                .required(List.of("username", "password"));
        Operation login = new Operation()
                .summary("Sign in; sets the SESSION cookie")
                .tags(List.of("session"))
                .security(List.of())
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType("application/x-www-form-urlencoded", new MediaType().schema(credentials))))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("signed in"))
                        .addApiResponse("401", new ApiResponse().description("wrong e-mail or password"))
                        .addApiResponse("429", new ApiResponse().description("RATE_LIMITED; see Retry-After")));
        Operation logout = new Operation()
                .summary("Sign out; ends the session on every instance")
                .tags(List.of("session"))
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("signed out")));

        return new OpenAPI()
                .info(new Info()
                        .title("Online Order API")
                        .version("1")
                        .description("""
                                Session-cookie API for the Online Order web client. Sign in with a form POST to /login
                                (username, password); the SESSION cookie authenticates later calls.

                                Errors are JSON: {"error": "<CODE>", "message": "..."}. Codes include OUT_OF_STOCK,
                                PRICE_CHANGED (with total_cents), MIXED_RESTAURANTS, EMPTY_CART, STATUS_CHANGED,
                                TOO_LATE_TO_CANCEL, IDEMPOTENCY_KEY_REUSED, VALIDATION_FAILED (with fields),
                                EMAIL_TAKEN and RATE_LIMITED (429, with Retry-After).

                                POST /orders takes an Idempotency-Key header: reuse it when retrying the same
                                checkout and the server replays the first result instead of placing a second order.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("session",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("SESSION")))
                .addSecurityItem(new SecurityRequirement().addList("session"))
                .path("/login", new PathItem().post(login))
                .path("/logout", new PathItem().post(logout));
    }
}
