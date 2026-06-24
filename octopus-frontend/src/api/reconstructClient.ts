import axios from 'axios';
import type { ApiResponse } from '../types';

const reconstructHttp = axios.create({
  baseURL: '/reconstruct',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
});

reconstructHttp.interceptors.response.use(
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

export default reconstructHttp;
