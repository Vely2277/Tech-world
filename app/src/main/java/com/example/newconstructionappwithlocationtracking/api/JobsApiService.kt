package com.example.newconstructionappwithlocationtracking.api

import com.example.newconstructionappwithlocationtracking.models.JobsResponse
import retrofit2.Response
import retrofit2.http.GET

interface JobsApiService {
    @GET("api/jobs")
    suspend fun getJobs(): Response<JobsResponse>
}
