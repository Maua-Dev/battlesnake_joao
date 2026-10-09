package starter;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class Logic {
    private static final Logger LOG = LoggerFactory.getLogger(Logic.class);

    // Representação de coordenada 2D imutável
    public static final class Coord {
        public final int x;
        public final int y;

        public Coord(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public Coord move(String direction) {
            switch (direction) {
                case "up":    return new Coord(x, y + 1);
                case "down":  return new Coord(x, y - 1);
                case "left":  return new Coord(x - 1, y);
                case "right": return new Coord(x + 1, y);
                default:      return this;
            }
        }

        public int manhattanDistance(Coord other) {
            return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Coord)) return false;
            Coord c = (Coord) o;
            return x == c.x && y == c.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    public static final class Snake {
        public final String id;
        public final int health;
        public final int length;
        public final Coord head;
        public final List<Coord> body;

        public Snake(String id, int health, int length, Coord head, List<Coord> body) {
            this.id = id;
            this.health = health;
            this.length = length;
            this.head = head;
            this.body = body;
        }
    }

    /**
     * Ponto de entrada chamado a cada turno pelo Battlesnake engine.
     */
    public static Map<String, String> move(JsonNode turnData) {
        int turn = turnData.path("turn").asInt(0);
        JsonNode boardNode = turnData.path("board");
        int width = boardNode.path("width").asInt(11);
        int height = boardNode.path("height").asInt(11);

        // Parse da nossa cobra (YOU)
        JsonNode youNode = turnData.path("you");
        String myId = youNode.path("id").asText();
        int myHealth = youNode.path("health").asInt(100);
        int myLength = youNode.path("length").asInt(3);
        Coord myHead = parseCoord(youNode.path("head"));
        List<Coord> myBody = parseCoordList(youNode.path("body"));
        Snake mySnake = new Snake(myId, myHealth, myLength, myHead, myBody);

        // Parse de todas as cobras
        List<Snake> opponents = new ArrayList<>();
        int maxOpponentLength = 0;
        for (JsonNode snakeNode : boardNode.path("snakes")) {
            String sId = snakeNode.path("id").asText();
            if (!sId.equals(myId)) {
                int sHealth = snakeNode.path("health").asInt(100);
                int sLength = snakeNode.path("length").asInt(3);
                Coord sHead = parseCoord(snakeNode.path("head"));
                List<Coord> sBody = parseCoordList(snakeNode.path("body"));
                opponents.add(new Snake(sId, sHealth, sLength, sHead, sBody));
                if (sLength > maxOpponentLength) {
                    maxOpponentLength = sLength;
                }
            }
        }

        // Parse de comidas e hazards
        List<Coord> foodList = parseCoordList(boardNode.path("food"));
        Set<Coord> hazards = new HashSet<>(parseCoordList(boardNode.path("hazards")));

        // 1. Mapeamento de obstáculos fixos (paredes e corpos)
        boolean[][] blocked = new boolean[width][height];

        // Marca corpo próprio
        for (int i = 0; i < myBody.size() - 1; i++) {
            Coord c = myBody.get(i);
            if (isInBounds(c, width, height)) {
                blocked[c.x][c.y] = true;
            }
        }

        // Marca corpos e cabeças dos oponentes
        for (Snake opp : opponents) {
            for (int i = 0; i < opp.body.size() - 1; i++) {
                Coord c = opp.body.get(i);
                if (isInBounds(c, width, height)) {
                    blocked[c.x][c.y] = true;
                }
            }
        }

        // 2. Mapeamento de zonas de perigo Head-to-Head
        Set<Coord> lethalDangerTiles = new HashSet<>();
        Set<Coord> killOpportunityTiles = new HashSet<>();
        String[] directions = {"up", "down", "left", "right"};

        for (Snake opp : opponents) {
            for (String dir : directions) {
                Coord oppNext = opp.head.move(dir);
                if (isInBounds(oppNext, width, height)) {
                    if (opp.length >= myLength) {
                        lethalDangerTiles.add(oppNext);
                    } else {
                        killOpportunityTiles.add(oppNext);
                    }
                }
            }
        }

        // 3. Avaliação de cada um dos 4 movimentos possíveis
        Map<String, Double> moveScores = new HashMap<>();

        for (String move : directions) {
            Coord next = myHead.move(move);

            // Filtro 1: Fora do tabuleiro = Morte instantânea
            if (!isInBounds(next, width, height)) {
                moveScores.put(move, -1_000_000.0);
                continue;
            }

            // Filtro 2: Colisão com corpos = Morte instantânea
            if (blocked[next.x][next.y]) {
                moveScores.put(move, -1_000_000.0);
                continue;
            }

            double score = 1000.0;

            // REGRA ESPECÍFICA: Spawn na segunda linha (y == 1 ou y == height - 2)
            // Se estiver nos 3 primeiros turnos e na 2ª linha, bloqueia subir para não morrer contra teto/paredes
            if (turn <= 3) {
                if ((myHead.y == 1 || myHead.y == height - 2) && "up".equals(move)) {
                    score -= 500_000.0;
                }
                // Previne bater nas bordas no início do jogo
                if (next.y == 0 || next.y == height - 1 || next.x == 0 || next.x == width - 1) {
                    score -= 100.0;
                }
            }

            // Filtro 3: Risco letal de colisão frontal de cabeças
            if (lethalDangerTiles.contains(next)) {
                score -= 80_000.0;
            } else if (killOpportunityTiles.contains(next)) {
                // Se formos maiores, podemos pressionar a casa para eliminar o menor
                score += 300.0;
            }

            // Filtro 4: Penalidade por Hazards
            if (hazards.contains(next)) {
                score -= (myHealth < 30 ? 2500.0 : 400.0);
            }

            // Heurística A: Flood Fill (Espaço livre disponível a partir deste movimento)
            int reachableSpace = calculateFloodFill(next, width, height, blocked, lethalDangerTiles);
            if (reachableSpace < myLength) {
                // Beco sem saída: risco altíssimo de ficar preso e se auto-esmagar
                score -= (myLength - reachableSpace) * 4_000.0;
            } else {
                score += reachableSpace * 25.0;
            }

            // Heurística B: Comportamento com Comida
            if (!foodList.isEmpty()) {
                Coord closestFood = findClosestCoord(next, foodList);
                int distFood = next.manhattanDistance(closestFood);

                boolean isStarving = myHealth < 35;
                boolean needGrowth = myLength <= maxOpponentLength;

                if (isStarving) {
                    score += (100 - distFood) * 250.0;
                } else if (needGrowth) {
                    score += (100 - distFood) * 80.0;
                } else {
                    // Cobra grande e com vida: consome comida só se estiver no caminho seguro
                    score += (100 - distFood) * 15.0;
                }
            }

            // Heurística C: Seguir a própria cauda (Tail Chasing em situações fechadas)
            Coord myTail = myBody.get(myBody.size() - 1);
            int distToTail = next.manhattanDistance(myTail);
            score += (100 - distToTail) * 10.0;

            // Heurística D: Manter proximidade do centro (Evita ficar encurralado nos cantos)
            double centerX = (width - 1) / 2.0;
            double centerY = (height - 1) / 2.0;
            double distToCenter = Math.abs(next.x - centerX) + Math.abs(next.y - centerY);
            score -= distToCenter * 8.0;

            // Borda do tabuleiro: leve penalidade por andar colado nas paredes
            if (next.x == 0 || next.x == width - 1 || next.y == 0 || next.y == height - 1) {
                score -= 40.0;
            }

            moveScores.put(move, score);
        }

        // Escolhe o movimento de maior pontuação
        String bestMove = "down";
        double highestScore = -Double.MAX_VALUE;

        for (Map.Entry<String, Double> entry : moveScores.entrySet()) {
            if (entry.getValue() > highestScore) {
                highestScore = entry.getValue();
                bestMove = entry.getKey();
            }
        }

        LOG.info("Turn {}: Moving {} (Score: {})", turn, bestMove, highestScore);

        Map<String, String> response = new HashMap<>();
        response.put("move", bestMove);
        return response;
    }

    /**
     * Algoritmo Flood Fill (BFS) para contar quantas casas livres estão conectadas.
     * Considera casas com risco de cabeça inimiga como semi-bloqueadas para evitar armadilhas.
     */
    private static int calculateFloodFill(Coord start, int width, int height, boolean[][] blocked, Set<Coord> dangerTiles) {
        boolean[][] visited = new boolean[width][height];
        Queue<Coord> queue = new ArrayDeque<>();

        visited[start.x][start.y] = true;
        queue.add(start);
        int count = 0;

        while (!queue.isEmpty()) {
            Coord curr = queue.poll();
            count++;

            for (String dir : new String[]{"up", "down", "left", "right"}) {
                Coord neighbor = curr.move(dir);
                if (isInBounds(neighbor, width, height)
                        && !visited[neighbor.x][neighbor.y]
                        && !blocked[neighbor.x][neighbor.y]
                        && !dangerTiles.contains(neighbor)) {
                    visited[neighbor.x][neighbor.y] = true;
                    queue.add(neighbor);
                }
            }
        }
        return count;
    }

    private static boolean isInBounds(Coord c, int width, int height) {
        return c.x >= 0 && c.x < width && c.y >= 0 && c.y < height;
    }

    private static Coord findClosestCoord(Coord origin, List<Coord> targets) {
        Coord closest = targets.get(0);
        int minDist = Integer.MAX_VALUE;
        for (Coord target : targets) {
            int d = origin.manhattanDistance(target);
            if (d < minDist) {
                minDist = d;
                closest = target;
            }
        }
        return closest;
    }

    private static Coord parseCoord(JsonNode node) {
        return new Coord(node.path("x").asInt(), node.path("y").asInt());
    }

    private static List<Coord> parseCoordList(JsonNode arrayNode) {
        List<Coord> list = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                list.add(parseCoord(item));
            }
        }
        return list;
    }
}