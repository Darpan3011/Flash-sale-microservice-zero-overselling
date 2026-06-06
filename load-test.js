import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    flash_sale_rush: {
      executor: 'constant-arrival-rate',
      rate: 100, // 100 requests per second
      timeUnit: '1s',
      duration: '5s', // Run for 5 seconds (500 total requests)
      preAllocatedVUs: 50,
      maxVUs: 100,
    },
  },
};

export default function () {
  // Using the API gateway port
  const saleId = __ENV.SALE_ID || '123e4567-e89b-12d3-a456-426614174000';
  const url = `http://localhost:8080/api/sales/${saleId}/buy`;
  
  // Generating a random user ID for each request to simulate 500 different users
  const userId = `user-${Math.floor(Math.random() * 100000)}`;
  

  const payload = JSON.stringify({});

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-User-ID': userId, // For rate-limiter and order tracking
    },
  };

  const res = http.post(url, payload, params);

  // We expect either 200 (Success - Order processing), 400 (Sold out/Rate limited), or 409 (Waitlisted)
  check(res, {
    'status is 200, 400, or 409': (r) => r.status === 200 || r.status === 400 || r.status === 409,
  });
}
