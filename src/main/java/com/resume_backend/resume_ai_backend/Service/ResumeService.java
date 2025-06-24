package com.resume_backend.resume_ai_backend.Service;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;

public interface ResumeService {
    Map<String,Object> generateResume(String userResumeDescription) throws IOException;
}
