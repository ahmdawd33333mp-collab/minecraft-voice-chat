FROM oven/bun:1-alpine
WORKDIR /app
COPY server.js ./server.js
ENV PORT=7860
USER bun
CMD ["bun", "server.js"]
