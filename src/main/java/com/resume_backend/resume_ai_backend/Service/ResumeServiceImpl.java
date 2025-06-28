package com.resume_backend.resume_ai_backend.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResumeServiceImpl implements ResumeService {

    // Inject the Gemini API key from application properties
    @Value("${gemini.api.key}")
    private String apiKey;

    private final WebClient webClient;

    // Constructor to initialize WebClient with the base URL for Google Generative Language API
    public ResumeServiceImpl(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("https://generativelanguage.googleapis.com").build();
    }

    /**
     * Generates resume content based on user's description using the Gemini API.
     * @param userResumeDescription The description provided by the user for the resume.
     * @return A map containing the parsed resume data from Gemini.
     * @throws IOException If there's an issue loading the prompt file.
     */
    @Override
    public Map<String, Object> generateResume(String userResumeDescription) throws IOException {
        // Load the prompt template from a file
        String promptString = this.loadPromptFromFile("resume_prompt.txt");
        // Populate the template with the user's description
        String promptContent = this.putValuesToTemplate(promptString, Map.of(
                "userDescription", userResumeDescription
        ));

        // Construct the request body as expected by the Gemini API (structured JSON)
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", promptContent)
                        ))
                )
        );

        // Define the model to be used.
        final String GEMINI_MODEL = "gemini-2.0-flash"; // Using gemini-2.0-flash for efficiency and availability

        String geminiResponse = null;
        try {
            // Make the POST request to the Gemini API
            geminiResponse = webClient.post()
                    // Construct the URI with the selected model and API key
                    .uri("/v1beta/models/" + GEMINI_MODEL + ":generateContent?key=" + apiKey)
                    .header("Content-Type", "application/json") // Set content type header
                    .bodyValue(requestBody) // Set the request body
                    .retrieve() // Retrieve the response
                    .bodyToMono(String.class) // Convert the response body to a String Mono
                    .block(); // Block and wait for the response (fine for small, single requests)
        } catch (WebClientResponseException e) {
            // Log the error response body if the API returns an error status code
            System.err.println("Error calling Gemini API: HTTP Status " + e.getStatusCode() + ", Response Body: " + e.getResponseBodyAsString());
            return Map.of("data", null, "error", "API call failed with status: " + e.getStatusCode());
        } catch (Exception e) {
            // Catch any other exceptions during the API call
            System.err.println("An unexpected error occurred during Gemini API call: " + e.getMessage());
            e.printStackTrace();
            return Map.of("data", null, "error", "An unexpected error occurred.");
        }

        // Parse the Gemini API response and return the extracted data
        return parseGeminiResponse(geminiResponse);
    }

    /**
     * Loads the content of a text file from the classpath.
     * @param filename The name of the file to load (e.g., "resume_prompt.txt").
     * @return The content of the file as a String.
     * @throws IOException If the file cannot be found or read.
     */
    private String loadPromptFromFile(String filename) throws IOException {
        ClassPathResource resource = new ClassPathResource(filename);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    /**
     * Replaces placeholders in a template string with actual values.
     * Placeholders are expected to be in the format `{{key}}`.
     * @param template The template string with placeholders.
     * @param values A map where keys are placeholder names and values are their replacements.
     * @return The template string with all placeholders replaced.
     */
    private String putValuesToTemplate(String template, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    /**
     * Parses the JSON response from the Gemini API to extract the generated text content.
     * It expects a specific nested structure to find the 'text' field.
     * This method is enhanced to strip Markdown code block fences (```json or ```)
     * and handle potential empty or malformed responses more gracefully.
     * @param response The raw JSON string received from the Gemini API.
     * @return A map containing the parsed data, or null if parsing fails, along with an error message.
     */
    private Map<String, Object> parseGeminiResponse(String response) {
        Map<String, Object> resultMap = new HashMap<>();
        if (response == null || response.trim().isEmpty()) {
            System.err.println("Gemini API returned an empty or null response.");
            resultMap.put("data", null);
            resultMap.put("error", "Empty or null response from Gemini API.");
            return resultMap;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);

            // Log the raw Gemini API response for debugging
            System.out.println("Raw Gemini API Response: " + response);

            JsonNode candidatesNode = root.get("candidates");
            if (candidatesNode == null || !candidatesNode.isArray() || candidatesNode.isEmpty()) {
                System.err.println("Gemini response does not contain 'candidates' array or it is empty.");
                JsonNode promptFeedback = root.get("promptFeedback");
                if (promptFeedback != null) {
                    System.err.println("Prompt Feedback: " + promptFeedback.toPrettyString());
                    // Check if there are safety ratings that might be blocking the response
                    JsonNode safetyRatings = promptFeedback.get("safetyRatings");
                    if (safetyRatings != null && safetyRatings.isArray()) {
                        safetyRatings.forEach(rating -> {
                            System.err.println("Safety Rating: Category=" + rating.get("category").asText() + ", Probability=" + rating.get("probability").asText());
                        });
                    }
                    resultMap.put("error", "Gemini API feedback: " + promptFeedback.toString());
                } else {
                    System.err.println("Unexpected Gemini response structure. No 'candidates' found.");
                    resultMap.put("error", "Unexpected response structure, no candidates.");
                }
                resultMap.put("data", null);
                return resultMap;
            }

            JsonNode firstCandidate = candidatesNode.get(0);
            JsonNode contentNode = firstCandidate.get("content");
            if (contentNode == null) {
                System.err.println("First candidate in Gemini response does not contain 'content'.");
                resultMap.put("data", null);
                resultMap.put("error", "Missing 'content' in Gemini response.");
                return resultMap;
            }

            JsonNode partsNode = contentNode.get("parts");
            if (partsNode == null || !partsNode.isArray() || partsNode.isEmpty()) {
                System.err.println("Content in Gemini response does not contain 'parts' array or it is empty.");
                resultMap.put("data", null);
                resultMap.put("error", "Missing 'parts' in Gemini response content.");
                return resultMap;
            }

            JsonNode firstPart = partsNode.get(0);
            JsonNode textNode = firstPart.get("text");
            if (textNode == null) {
                System.err.println("First part in Gemini response does not contain 'text'.");
                resultMap.put("data", null);
                resultMap.put("error", "Missing 'text' in Gemini response part.");
                return resultMap;
            }

            String jsonText = textNode.asText();

            // Log the extracted text before stripping
            System.out.println("Extracted raw text from Gemini (before stripping): \n" + jsonText);

            // Robust stripping of Markdown code block fences
            // This regex handles ```json`, ``` `, leading/trailing newlines, and trailing ``` `
            Pattern pattern = Pattern.compile("```(?:json\\s*)?\\s*([\\s\\S]*?)\\s*```");
            Matcher matcher = pattern.matcher(jsonText.trim());

            if (matcher.matches()) {
                jsonText = matcher.group(1).trim(); // Extract content inside the code block
                System.out.println("Stripped Markdown code block. Remaining text: \n" + jsonText);
            } else {
                // If it's not wrapped in ```, assume it's just raw JSON and trim
                jsonText = jsonText.trim();
                System.out.println("Text not wrapped in markdown code block. Using as is: \n" + jsonText);
            }

            // Attempt to parse the extracted and stripped text as JSON
            JsonNode finalJsonNode = mapper.readTree(jsonText);
            resultMap.put("data", mapper.convertValue(finalJsonNode, Map.class));
            System.out.println("Successfully parsed Gemini response content.");

        } catch (Exception e) {
            // Log any errors encountered during parsing of the Gemini response
            resultMap.put("data", null);
            System.err.println("Error parsing Gemini response JSON structure or extracted text (after stripping markdown): " + e.getMessage());
            e.printStackTrace(); // Print stack trace for detailed debugging
            resultMap.put("error", "Failed to parse Gemini response: " + e.getMessage() + ". Check if the LLM output is valid JSON.");
        }
        return resultMap;
    }
}
