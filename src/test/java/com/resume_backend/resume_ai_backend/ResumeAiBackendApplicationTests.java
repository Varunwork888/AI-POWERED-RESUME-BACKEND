package com.resume_backend.resume_ai_backend;

import com.resume_backend.resume_ai_backend.Service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
class ResumeAiBackendApplicationTests {
	@Autowired
	private ResumeService resumeService;

	@Test
	void contextLoads() throws IOException {
		resumeService.generateResume("i am varun kumar with 2 years of exp of java ");
	}

}
