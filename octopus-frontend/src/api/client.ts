import axios from 'axios';
import type { ApiResponse } from '../types';

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

// Auto-unwrap ApiResponse
http.interceptors.response.use(
  (res) => {
    const body = res.data as ApiResponse<unknown>;
    if (body.success !== undefined && !body.success) {
      return Promise.reject(new Error(body.message || 'Request failed'));
    }
    res.data = body.data !== undefined ? body.data : (body as unknown);
    return res;
  },
  (err) => {
    const msg = err.response?.data?.message || err.message || 'Network error';
    return Promise.reject(new Error(msg));
  },
);

export default http;
