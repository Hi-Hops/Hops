import { createRealtimeServer } from './server.js';
import { loadEnvironment } from './config/env.js';

const { port, config } = loadEnvironment();

const server = createRealtimeServer(config);

server.http.listen(port, () => console.log(`Hops realtime listening on ${port}`));

for (const signal of ['SIGINT', 'SIGTERM'])
  process.once(signal, () => {
    void server.close();
  });
