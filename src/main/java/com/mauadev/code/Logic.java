package com.maua;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public class Logic {

    /**
     * Função principal de decisão de movimento da Battlesnake.
     * Retorna a direção escolhida: "up", "down", "left" ou "right".
     */
    public static String move(JsonNode moveRequest) {
        int width = moveRequest.get("board").get("width").asInt();
        int height = moveRequest.get("board").get("height").asInt();
        int turn = moveRequest.has("turn") ? moveRequest.get("turn").asInt() : 0;

        JsonNode you = moveRequest.get("you");
        int myHealth = you.get("health").asInt();
        int myLength = you.get("length").asInt();

        JsonNode myHead = you.get("head");
        int headX = myHead.get("x").asInt();
        int headY = myHead.get("y").asInt();

        // 1. Mapear direções possíveis a partir da cabeça
        Map<String, Point> possibleMoves = new HashMap<>();
        possibleMoves.put("up", new Point(headX, headY + 1));
        possibleMoves.put("down", new Point(headX, headY - 1));
        possibleMoves.put("left", new Point(headX - 1, headY));
        possibleMoves.put("right", new Point(headX + 1, headY));

        // 2. Mapear obstáculos (corpos de cobras) e zonas de perigo de adversários
        Set<Point> obstacles = new HashSet<>();
        List<Point> dangerousHeadZones = new ArrayList<>();
        List<Point> targetHeadZones = new ArrayList<>();

        JsonNode snakes = moveRequest.get("board").get("snakes");
        for (JsonNode snake : snakes) {
            JsonNode body = snake.get("body");
            int enemyLength = snake.get("length").asInt();
            boolean isMe = snake.get("id").asText().equals(you.get("id").asText());

            for (int i = 0; i < body.size(); i++) {
                JsonNode seg = body.get(i);
                Point p = new Point(seg.get("x").asInt(), seg.get("y").asInt());

                // A ponta da cauda vai avançar no próximo turno (a menos que a cobra tenha acabado de comer)
                boolean isTail = (i == body.size() - 1);
                if (isTail) {
                    JsonNode segBeforeTail = body.get(body.size() - 2);
                    boolean hasEaten = seg.get("x").asInt() == segBeforeTail.get("x").asInt() 
                                    && seg.get("y").asInt() == segBeforeTail.get("y").asInt();
                    if (!hasEaten) {
                        continue; // Cauda vai liberar este quadrado
                    }
                }
                obstacles.add(p);
            }

            // Mapear vizinhança das cabeças inimigas
            if (!isMe) {
                JsonNode enemyHead = snake.get("head");
                int ehX = enemyHead.get("x").asInt();
                int ehY = enemyHead.get("y").asInt();
                Point[] adj = {
                    new Point(ehX, ehY + 1),
                    new Point(ehX, ehY - 1),
                    new Point(ehX - 1, ehY),
                    new Point(ehX + 1, ehY)
                };
                for (Point p : adj) {
                    if (enemyLength >= myLength) {
                        dangerousHeadZones.add(p);
                    } else {
                        targetHeadZones.add(p);
                    }
                }
            }
        }

        // 3. Avaliar e pontuar movimentos válidos
        Map<String, Double> moveScores = new HashMap<>();

        for (Map.Entry<String, Point> entry : possibleMoves.entrySet()) {
            String dir = entry.getKey();
            Point target = entry.getValue();

            // REGRA: Não bater nas paredes do mapa
            if (target.x < 0 || target.x >= width || target.y < 0 || target.y >= height) {
                continue;
            }

            // REGRA SOLICITADA: Se estiver na segunda linha (y == 1), é proibido ir para cima ("up")
            if (headY == 1 && dir.equals("up")) {
                continue;
            }

            // REGRA: Não bater no corpo de nenhuma cobra
            if (obstacles.contains(target)) {
                continue;
            }

            // Pontuação inicial para um movimento seguro
            double score = 1000.0;

            // Perigo: Evitar colisão frontal com cobras maiores ou de mesmo tamanho
            if (dangerousHeadZones.contains(target)) {
                score -= 800.0;
            }

            // Oportunidade: Atacar cobras menores
            if (targetHeadZones.contains(target)) {
                score += 300.0;
            }

            // Algoritmo Flood-Fill: Verificar espaço transitável disponível
            int space = countReachableSpace(target, width, height, obstacles);
            if (space < myLength) {
                score -= (myLength - space) * 150.0; // Penalidade alta por entrar em becos sem saída
            } else {
                score += space * 10.0; // Bônus por área aberta
            }

            // Busca por comida
            JsonNode foodList = moveRequest.get("board").get("food");
            Point nearestFood = findNearestFood(target, foodList);

            if (nearestFood != null) {
                int dist = Math.abs(target.x - nearestFood.x) + Math.abs(target.y - nearestFood.y);
                if (myHealth < 60 || myLength < 10) {
                    score += (100.0 - dist * 10.0) * 3.0; // Alta prioridade para comer
                } else {
                    score += (50.0 - dist * 5.0);
                }
            }

            // Bônus de posicionamento central
            double centerX = (width - 1) / 2.0;
            double centerY = (height - 1) / 2.0;
            double distToCenter = Math.abs(target.x - centerX) + Math.abs(target.y - centerY);
            score -= distToCenter * 2.0;

            moveScores.put(dir, score);
        }

        // 4. Selecionar o melhor movimento com maior pontuação
        if (moveScores.isEmpty()) {
            // Plano de emergência caso todas as opções sejam perigosas
            for (Map.Entry<String, Point> entry : possibleMoves.entrySet()) {
                Point t = entry.getValue();
                if (t.x >= 0 && t.x < width && t.y >= 0 && t.y < height) {
                    return entry.getKey();
                }
            }
            return "up";
        }

        String bestMove = "up";
        double maxScore = -Double.MAX_VALUE;

        for (Map.Entry<String, Double> entry : moveScores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                bestMove = entry.getKey();
            }
        }

        return bestMove;
    }

    /**
     * Algoritmo BFS para calcular o espaço livre alcançável a partir de um ponto.
     */
    private static int countReachableSpace(Point start, int width, int height, Set<Point> obstacles) {
        Set<Point> visited = new HashSet<>();
        Queue<Point> queue = new LinkedList<>();

        queue.add(start);
        visited.add(start);

        int count = 0;
        int maxDepth = 60; // Limite para garantir resposta dentro do tempo limite da API (<200ms)

        while (!queue.isEmpty() && count < maxDepth) {
            Point current = queue.poll();
            count++;

            Point[] neighbors = {
                new Point(current.x, current.y + 1),
                new Point(current.x, current.y - 1),
                new Point(current.x - 1, current.y),
                new Point(current.x + 1, current.y)
            };

            for (Point next : neighbors) {
                if (next.x >= 0 && next.x < width && next.y >= 0 && next.y < height) {
                    if (!obstacles.contains(next) && !visited.contains(next)) {
                        visited.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Localiza a comida mais próxima da posição atual.
     */
    private static Point findNearestFood(Point start, JsonNode foodList) {
        Point nearest = null;
        int minDist = Integer.MAX_VALUE;

        if (foodList != null && foodList.isArray()) {
            for (JsonNode food : foodList) {
                Point f = new Point(food.get("x").asInt(), food.get("y").asInt());
                int dist = Math.abs(start.x - f.x) + Math.abs(start.y - f.y);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = f;
                }
            }
        }
        return nearest;
    }

    /**
     * Classe utilitária para representar coordenadas no tabuleiro.
     */
    private static class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point point = (Point) o;
            return x == point.x && y == point.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }
}