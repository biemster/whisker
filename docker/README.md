# Whisker Home Server Docker Deployment

This folder contains container deployment configurations for running the Whisker Home Server on Docker, Podman, Kubernetes, Cloud Run, and Microsoft WSL Containers.

### Fast Launch with Docker Compose
From the project root:
```bash
docker compose up -d
```

### Direct Build with Docker
```bash
# Using root Dockerfile:
docker build -t whisker-server .

# Or using Dockerfile.server:
docker build -f Dockerfile.server -t whisker-server .

# Run with persistent volume:
docker run -d \
  --name whisker-server \
  -p 3000:3000 \
  -v whisker-data:/data \
  -e ADMIN_PASSWORD="your-strong-password" \
  whisker-server
```
