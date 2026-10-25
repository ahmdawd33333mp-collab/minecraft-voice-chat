FROM oven/bun:1-alpine
WORKDIR /app
COPY relay/server.js ./server.js
USER bun
CMD ["bun", "server.js"]
