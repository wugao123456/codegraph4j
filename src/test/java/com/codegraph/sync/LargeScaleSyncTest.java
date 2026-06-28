package com.codegraph.sync;

import com.codegraph.cli.CodeGraphCli;
import com.codegraph.db.DatabaseConnection;
import com.codegraph.db.QueryBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 大规模模拟测试 — 生成真实项目结构的 mock 文件（~50+ 个 Java 文件），
 * 测试全量同步流程与增量同步性能。
 *
 * 项目结构模拟：
 * src/main/java/com/example/
 *   ├── controller/    (8 files)
 *   ├── service/       (8 files)
 *   ├── repository/    (6 files)
 *   ├── model/         (12 files: entity + dto)
 *   ├── config/        (4 files)
 *   ├── util/          (4 files)
 *   └── exception/     (3 files)
 *
 * 此外在 src/test/java 下还有 8 个测试文件。
 */
public class LargeScaleSyncTest {

    private Path tempDir;
    private Path srcMainJava;
    private int totalJavaFiles = 0;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("codegraph-large-sync-");
        srcMainJava = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMainJava);
        System.out.println("临时项目: " + tempDir);
    }

    @After
    public void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    // ===== 文件生成器 =====

    /** 在相对 src/main/java/com/example 的子目录生成单个 Java 文件 */
    private void javaFile(String subPackage, String className, String body) throws IOException {
        Path pkg = srcMainJava.resolve(subPackage);
        Files.createDirectories(pkg);
        String pkgDecl = subPackage.isEmpty() ? "com.example" : "com.example." + subPackage.replace('/', '.');
        String content = "package " + pkgDecl + ";\n\n" + body;
        Files.write(pkg.resolve(className + ".java"), content.getBytes(StandardCharsets.UTF_8));
        totalJavaFiles++;
    }

    /** 生成一个普通 Java 文件（含 import） */
    private void javaFileWithImport(String subPackage, String className, String imports, String body) throws IOException {
        Path pkg = srcMainJava.resolve(subPackage);
        Files.createDirectories(pkg);
        String pkgDecl = subPackage.isEmpty() ? "com.example" : "com.example." + subPackage.replace('/', '.');
        String content = "package " + pkgDecl + ";\n\n" + imports + "\n" + body;
        Files.write(pkg.resolve(className + ".java"), content.getBytes(StandardCharsets.UTF_8));
        totalJavaFiles++;
    }

    /** 生成所有 mock 文件 */
    private void generateProject() throws Exception {
        // ===== model/entity =====
        javaFile("model/entity", "User",
                "public class User {\n" +
                "    private Long id;\n" +
                "    private String username;\n" +
                "    private String email;\n" +
                "    private int age;\n" +
                "    public Long getId() { return id; }\n" +
                "    public void setId(Long id) { this.id = id; }\n" +
                "    public String getUsername() { return username; }\n" +
                "    public void setUsername(String username) { this.username = username; }\n" +
                "    public String getEmail() { return email; }\n" +
                "    public void setEmail(String email) { this.email = email; }\n" +
                "    public int getAge() { return age; }\n" +
                "    public void setAge(int age) { this.age = age; }\n" +
                "}\n");

        javaFile("model/entity", "Order",
                "import java.math.BigDecimal;\n" +
                "import java.time.LocalDateTime;\n" +
                "public class Order {\n" +
                "    private Long id;\n" +
                "    private Long userId;\n" +
                "    private BigDecimal amount;\n" +
                "    private String status;\n" +
                "    private LocalDateTime createdAt;\n" +
                "    public Long getId() { return id; }\n" +
                "    public void setId(Long id) { this.id = id; }\n" +
                "    public Long getUserId() { return userId; }\n" +
                "    public void setUserId(Long userId) { this.userId = userId; }\n" +
                "    public BigDecimal getAmount() { return amount; }\n" +
                "    public void setAmount(BigDecimal amount) { this.amount = amount; }\n" +
                "    public String getStatus() { return status; }\n" +
                "    public void setStatus(String status) { this.status = status; }\n" +
                "    public LocalDateTime getCreatedAt() { return createdAt; }\n" +
                "    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }\n" +
                "}\n");

        javaFile("model/entity", "Product",
                "import java.math.BigDecimal;\n" +
                "public class Product {\n" +
                "    private Long id;\n" +
                "    private String name;\n" +
                "    private BigDecimal price;\n" +
                "    private int stock;\n" +
                "    private String category;\n" +
                "    public Long getId() { return id; }\n" +
                "    public void setId(Long id) { this.id = id; }\n" +
                "    public String getName() { return name; }\n" +
                "    public void setName(String name) { this.name = name; }\n" +
                "    public BigDecimal getPrice() { return price; }\n" +
                "    public void setPrice(BigDecimal price) { this.price = price; }\n" +
                "    public int getStock() { return stock; }\n" +
                "    public void setStock(int stock) { this.stock = stock; }\n" +
                "    public String getCategory() { return category; }\n" +
                "    public void setCategory(String category) { this.category = category; }\n" +
                "}\n");

        javaFile("model/entity", "Category",
                "public class Category {\n" +
                "    private Long id;\n" +
                "    private String name;\n" +
                "    private Long parentId;\n" +
                "    public Long getId() { return id; }\n" +
                "    public void setId(Long id) { this.id = id; }\n" +
                "    public String getName() { return name; }\n" +
                "    public void setName(String name) { this.name = name; }\n" +
                "    public Long getParentId() { return parentId; }\n" +
                "    public void setParentId(Long parentId) { this.parentId = parentId; }\n" +
                "}\n");

        // ===== model/dto =====
        javaFileWithImport("model/dto", "UserDTO",
                "import com.example.model.entity.User;\n",
                "public class UserDTO {\n" +
                "    private String username;\n" +
                "    private String email;\n" +
                "    public static UserDTO from(User user) {\n" +
                "        UserDTO dto = new UserDTO();\n" +
                "        dto.username = user.getUsername();\n" +
                "        dto.email = user.getEmail();\n" +
                "        return dto;\n" +
                "    }\n" +
                "    public String getUsername() { return username; }\n" +
                "    public void setUsername(String username) { this.username = username; }\n" +
                "    public String getEmail() { return email; }\n" +
                "    public void setEmail(String email) { this.email = email; }\n" +
                "}\n");

        javaFileWithImport("model/dto", "OrderDTO",
                "import java.math.BigDecimal;\nimport java.time.LocalDateTime;\n",
                "public class OrderDTO {\n" +
                "    private Long id;\n" +
                "    private BigDecimal amount;\n" +
                "    private String status;\n" +
                "    private LocalDateTime createdAt;\n" +
                "    public Long getId() { return id; }\n" +
                "    public void setId(Long id) { this.id = id; }\n" +
                "    public BigDecimal getAmount() { return amount; }\n" +
                "    public void setAmount(BigDecimal amount) { this.amount = amount; }\n" +
                "    public String getStatus() { return status; }\n" +
                "    public void setStatus(String status) { this.status = status; }\n" +
                "    public LocalDateTime getCreatedAt() { return createdAt; }\n" +
                "    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }\n" +
                "}\n");

        javaFile("model/dto", "ProductDTO",
                "import java.math.BigDecimal;\n" +
                "public class ProductDTO {\n" +
                "    private String name;\n" +
                "    private BigDecimal price;\n" +
                "    private String category;\n" +
                "    public String getName() { return name; }\n" +
                "    public void setName(String name) { this.name = name; }\n" +
                "    public BigDecimal getPrice() { return price; }\n" +
                "    public void setPrice(BigDecimal price) { this.price = price; }\n" +
                "    public String getCategory() { return category; }\n" +
                "    public void setCategory(String category) { this.category = category; }\n" +
                "}\n");

        javaFile("model/dto", "CreateUserRequest",
                "public class CreateUserRequest {\n" +
                "    private String username;\n" +
                "    private String email;\n" +
                "    private int age;\n" +
                "    public String getUsername() { return username; }\n" +
                "    public void setUsername(String username) { this.username = username; }\n" +
                "    public String getEmail() { return email; }\n" +
                "    public void setEmail(String email) { this.email = email; }\n" +
                "    public int getAge() { return age; }\n" +
                "    public void setAge(int age) { this.age = age; }\n" +
                "}\n");

        javaFile("model/dto", "UpdateUserRequest",
                "public class UpdateUserRequest {\n" +
                "    private String email;\n" +
                "    private int age;\n" +
                "    public String getEmail() { return email; }\n" +
                "    public void setEmail(String email) { this.email = email; }\n" +
                "    public int getAge() { return age; }\n" +
                "    public void setAge(int age) { this.age = age; }\n" +
                "}\n");

        javaFile("model/dto", "ApiResponse",
                "public class ApiResponse<T> {\n" +
                "    private int code;\n" +
                "    private String message;\n" +
                "    private T data;\n" +
                "    public static <T> ApiResponse<T> ok(T data) {\n" +
                "        ApiResponse<T> r = new ApiResponse<>();\n" +
                "        r.code = 200;\n" +
                "        r.message = \"success\";\n" +
                "        r.data = data;\n" +
                "        return r;\n" +
                "    }\n" +
                "    public static <T> ApiResponse<T> error(int code, String message) {\n" +
                "        ApiResponse<T> r = new ApiResponse<>();\n" +
                "        r.code = code;\n" +
                "        r.message = message;\n" +
                "        return r;\n" +
                "    }\n" +
                "    public int getCode() { return code; }\n" +
                "    public String getMessage() { return message; }\n" +
                "    public T getData() { return data; }\n" +
                "}\n");

        javaFile("model/dto", "PageRequest",
                "public class PageRequest {\n" +
                "    private int page = 1;\n" +
                "    private int size = 20;\n" +
                "    private String sortBy;\n" +
                "    private String sortDir;\n" +
                "    public int getPage() { return page; }\n" +
                "    public void setPage(int page) { this.page = page; }\n" +
                "    public int getSize() { return size; }\n" +
                "    public void setSize(int size) { this.size = size; }\n" +
                "    public String getSortBy() { return sortBy; }\n" +
                "    public void setSortBy(String sortBy) { this.sortBy = sortBy; }\n" +
                "    public String getSortDir() { return sortDir; }\n" +
                "    public void setSortDir(String sortDir) { this.sortDir = sortDir; }\n" +
                "}\n");

        javaFileWithImport("model/dto", "PageResponse",
                "import java.util.List;\n",
                "public class PageResponse<T> {\n" +
                "    private int page;\n" +
                "    private int size;\n" +
                "    private long total;\n" +
                "    private List<T> items;\n" +
                "    public int getPage() { return page; }\n" +
                "    public void setPage(int page) { this.page = page; }\n" +
                "    public int getSize() { return size; }\n" +
                "    public void setSize(int size) { this.size = size; }\n" +
                "    public long getTotal() { return total; }\n" +
                "    public void setTotal(long total) { this.total = total; }\n" +
                "    public List<T> getItems() { return items; }\n" +
                "    public void setItems(List<T> items) { this.items = items; }\n" +
                "}\n");

        // ===== controller =====
        javaFileWithImport("controller", "UserController",
                "import com.example.model.dto.*;\n" +
                "import com.example.service.UserService;\n" +
                "import java.util.List;\n",
                "public class UserController {\n" +
                "    private UserService userService;\n" +
                "    public ApiResponse<UserDTO> getUser(Long id) {\n" +
                "        UserDTO user = userService.findById(id);\n" +
                "        return ApiResponse.ok(user);\n" +
                "    }\n" +
                "    public ApiResponse<PageResponse<UserDTO>> listUsers(PageRequest req) {\n" +
                "        PageResponse<UserDTO> page = userService.list(req);\n" +
                "        return ApiResponse.ok(page);\n" +
                "    }\n" +
                "    public ApiResponse<UserDTO> createUser(CreateUserRequest req) {\n" +
                "        UserDTO user = userService.create(req);\n" +
                "        return ApiResponse.ok(user);\n" +
                "    }\n" +
                "    public ApiResponse<UserDTO> updateUser(Long id, UpdateUserRequest req) {\n" +
                "        UserDTO user = userService.update(id, req);\n" +
                "        return ApiResponse.ok(user);\n" +
                "    }\n" +
                "    public ApiResponse<Void> deleteUser(Long id) {\n" +
                "        userService.delete(id);\n" +
                "        return ApiResponse.ok(null);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "OrderController",
                "import com.example.model.dto.*;\nimport com.example.service.OrderService;\n",
                "public class OrderController {\n" +
                "    private OrderService orderService;\n" +
                "    public ApiResponse<OrderDTO> getOrder(Long id) {\n" +
                "        OrderDTO order = orderService.findById(id);\n" +
                "        return ApiResponse.ok(order);\n" +
                "    }\n" +
                "    public ApiResponse<PageResponse<OrderDTO>> listOrders(PageRequest req) {\n" +
                "        PageResponse<OrderDTO> page = orderService.list(req);\n" +
                "        return ApiResponse.ok(page);\n" +
                "    }\n" +
                "    public ApiResponse<OrderDTO> createOrder(OrderDTO dto) {\n" +
                "        OrderDTO created = orderService.create(dto);\n" +
                "        return ApiResponse.ok(created);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "ProductController",
                "import com.example.model.dto.*;\nimport com.example.service.ProductService;\n",
                "public class ProductController {\n" +
                "    private ProductService productService;\n" +
                "    public ApiResponse<ProductDTO> getProduct(Long id) {\n" +
                "        return ApiResponse.ok(productService.findById(id));\n" +
                "    }\n" +
                "    public ApiResponse<PageResponse<ProductDTO>> searchProducts(String keyword, PageRequest req) {\n" +
                "        return ApiResponse.ok(productService.search(keyword, req));\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "CategoryController",
                "import com.example.model.dto.*;\nimport com.example.model.entity.Category;\n" +
                "import com.example.service.CategoryService;\nimport java.util.List;\n",
                "public class CategoryController {\n" +
                "    private CategoryService categoryService;\n" +
                "    public ApiResponse<List<Category>> listAll() {\n" +
                "        return ApiResponse.ok(categoryService.findAll());\n" +
                "    }\n" +
                "    public ApiResponse<Category> getById(Long id) {\n" +
                "        return ApiResponse.ok(categoryService.findById(id));\n" +
                "    }\n" +
                "}\n");

        javaFile("controller", "HealthController",
                "public class HealthController {\n" +
                "    public String health() {\n" +
                "        return \"OK\";\n" +
                "    }\n" +
                "    public String ping() {\n" +
                "        return \"pong\";\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "AuthController",
                "import com.example.model.dto.ApiResponse;\n",
                "public class AuthController {\n" +
                "    public ApiResponse<String> login(String username, String password) {\n" +
                "        return ApiResponse.ok(\"token-xxx\");\n" +
                "    }\n" +
                "    public ApiResponse<Void> logout() {\n" +
                "        return ApiResponse.ok(null);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "FileController",
                "import com.example.model.dto.ApiResponse;\n" +
                "import com.example.service.FileService;\n",
                "public class FileController {\n" +
                "    private FileService fileService;\n" +
                "    public ApiResponse<String> upload(String fileName, byte[] data) {\n" +
                "        String url = fileService.store(fileName, data);\n" +
                "        return ApiResponse.ok(url);\n" +
                "    }\n" +
                "    public byte[] download(String fileId) {\n" +
                "        return fileService.retrieve(fileId);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("controller", "AdminController",
                "import com.example.model.dto.*;\n" +
                "import com.example.service.AdminService;\n" +
                "import java.util.Map;\n",
                "public class AdminController {\n" +
                "    private AdminService adminService;\n" +
                "    public ApiResponse<Map<String, Object>> dashboard() {\n" +
                "        return ApiResponse.ok(adminService.getDashboard());\n" +
                "    }\n" +
                "    public ApiResponse<Void> clearCache() {\n" +
                "        adminService.clearCache();\n" +
                "        return ApiResponse.ok(null);\n" +
                "    }\n" +
                "}\n");

        // ===== service =====
        javaFileWithImport("service", "UserService",
                "import com.example.model.dto.*;\nimport com.example.model.entity.User;\n" +
                "import com.example.repository.UserRepository;\n" +
                "import java.util.List;\nimport java.util.stream.Collectors;\n",
                "public class UserService {\n" +
                "    private UserRepository userRepository;\n" +
                "    public UserDTO findById(Long id) {\n" +
                "        User user = userRepository.findById(id);\n" +
                "        return UserDTO.from(user);\n" +
                "    }\n" +
                "    public PageResponse<UserDTO> list(PageRequest req) {\n" +
                "        List<User> users = userRepository.findAll();\n" +
                "        List<UserDTO> dtos = users.stream().map(UserDTO::from).collect(Collectors.toList());\n" +
                "        PageResponse<UserDTO> resp = new PageResponse<>();\n" +
                "        resp.setItems(dtos);\n" +
                "        resp.setTotal(dtos.size());\n" +
                "        return resp;\n" +
                "    }\n" +
                "    public UserDTO create(CreateUserRequest req) {\n" +
                "        User user = new User();\n" +
                "        user.setUsername(req.getUsername());\n" +
                "        user.setEmail(req.getEmail());\n" +
                "        user.setAge(req.getAge());\n" +
                "        userRepository.save(user);\n" +
                "        return UserDTO.from(user);\n" +
                "    }\n" +
                "    public UserDTO update(Long id, UpdateUserRequest req) {\n" +
                "        User user = userRepository.findById(id);\n" +
                "        if (req.getEmail() != null) user.setEmail(req.getEmail());\n" +
                "        user.setAge(req.getAge());\n" +
                "        userRepository.save(user);\n" +
                "        return UserDTO.from(user);\n" +
                "    }\n" +
                "    public void delete(Long id) {\n" +
                "        userRepository.deleteById(id);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("service", "OrderService",
                "import com.example.model.dto.*;\nimport com.example.model.entity.Order;\n" +
                "import com.example.repository.OrderRepository;\n" +
                "import java.math.BigDecimal;\nimport java.time.LocalDateTime;\n" +
                "import java.util.List;\nimport java.util.stream.Collectors;\n",
                "public class OrderService {\n" +
                "    private OrderRepository orderRepository;\n" +
                "    public OrderDTO findById(Long id) {\n" +
                "        Order order = orderRepository.findById(id);\n" +
                "        return convertToDTO(order);\n" +
                "    }\n" +
                "    public PageResponse<OrderDTO> list(PageRequest req) {\n" +
                "        List<Order> orders = orderRepository.findAll();\n" +
                "        List<OrderDTO> dtos = orders.stream().map(this::convertToDTO).collect(Collectors.toList());\n" +
                "        PageResponse<OrderDTO> resp = new PageResponse<>();\n" +
                "        resp.setItems(dtos);\n" +
                "        resp.setTotal(dtos.size());\n" +
                "        return resp;\n" +
                "    }\n" +
                "    public OrderDTO create(OrderDTO dto) {\n" +
                "        Order order = new Order();\n" +
                "        order.setAmount(dto.getAmount());\n" +
                "        order.setStatus(\"PENDING\");\n" +
                "        order.setCreatedAt(LocalDateTime.now());\n" +
                "        orderRepository.save(order);\n" +
                "        return convertToDTO(order);\n" +
                "    }\n" +
                "    private OrderDTO convertToDTO(Order order) {\n" +
                "        OrderDTO dto = new OrderDTO();\n" +
                "        dto.setId(order.getId());\n" +
                "        dto.setAmount(order.getAmount());\n" +
                "        dto.setStatus(order.getStatus());\n" +
                "        dto.setCreatedAt(order.getCreatedAt());\n" +
                "        return dto;\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("service", "ProductService",
                "import com.example.model.dto.*;\nimport com.example.model.entity.Product;\n" +
                "import com.example.repository.ProductRepository;\n" +
                "import java.util.List;\nimport java.util.stream.Collectors;\n",
                "public class ProductService {\n" +
                "    private ProductRepository productRepository;\n" +
                "    public ProductDTO findById(Long id) {\n" +
                "        Product p = productRepository.findById(id);\n" +
                "        return convertToDTO(p);\n" +
                "    }\n" +
                "    public PageResponse<ProductDTO> search(String keyword, PageRequest req) {\n" +
                "        List<Product> products = productRepository.search(keyword);\n" +
                "        List<ProductDTO> dtos = products.stream().map(this::convertToDTO).collect(Collectors.toList());\n" +
                "        PageResponse<ProductDTO> resp = new PageResponse<>();\n" +
                "        resp.setItems(dtos);\n" +
                "        resp.setTotal(dtos.size());\n" +
                "        return resp;\n" +
                "    }\n" +
                "    private ProductDTO convertToDTO(Product p) {\n" +
                "        ProductDTO dto = new ProductDTO();\n" +
                "        dto.setName(p.getName());\n" +
                "        dto.setPrice(p.getPrice());\n" +
                "        dto.setCategory(p.getCategory());\n" +
                "        return dto;\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("service", "CategoryService",
                "import com.example.model.entity.Category;\n" +
                "import com.example.repository.CategoryRepository;\n" +
                "import java.util.List;\n",
                "public class CategoryService {\n" +
                "    private CategoryRepository categoryRepository;\n" +
                "    public List<Category> findAll() {\n" +
                "        return categoryRepository.findAll();\n" +
                "    }\n" +
                "    public Category findById(Long id) {\n" +
                "        return categoryRepository.findById(id);\n" +
                "    }\n" +
                "    public List<Category> findByParent(Long parentId) {\n" +
                "        return categoryRepository.findByParentId(parentId);\n" +
                "    }\n" +
                "}\n");

        javaFile("service", "FileService",
                "import java.util.Map;\nimport java.util.concurrent.ConcurrentHashMap;\n" +
                "public class FileService {\n" +
                "    private final Map<String, byte[]> storage = new ConcurrentHashMap<>();\n" +
                "    public String store(String fileName, byte[] data) {\n" +
                "        String id = java.util.UUID.randomUUID().toString();\n" +
                "        storage.put(id, data);\n" +
                "        return \"/files/\" + id;\n" +
                "    }\n" +
                "    public byte[] retrieve(String fileId) {\n" +
                "        return storage.get(fileId);\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("service", "AdminService",
                "import java.util.Map;\nimport java.util.HashMap;\n",
                "public class AdminService {\n" +
                "    public Map<String, Object> getDashboard() {\n" +
                "        Map<String, Object> data = new HashMap<>();\n" +
                "        data.put(\"users\", 1024);\n" +
                "        data.put(\"orders\", 5678);\n" +
                "        data.put(\"products\", 256);\n" +
                "        return data;\n" +
                "    }\n" +
                "    public void clearCache() {\n" +
                "        // clear all caches\n" +
                "    }\n" +
                "}\n");

        javaFile("service", "EmailService",
                "public class EmailService {\n" +
                "    public void sendWelcome(String email, String username) {\n" +
                "        // send welcome email\n" +
                "    }\n" +
                "    public void sendPasswordReset(String email, String token) {\n" +
                "        // send password reset email\n" +
                "    }\n" +
                "    public void sendOrderConfirmation(String email, Long orderId) {\n" +
                "        // send order confirmation\n" +
                "    }\n" +
                "}\n");

        javaFile("service", "NotificationService",
                "import com.example.model.entity.User;\n" +
                "public class NotificationService {\n" +
                "    public void notifyUser(User user, String message) {\n" +
                "        // push notification to user\n" +
                "    }\n" +
                "    public void broadcast(String topic, String message) {\n" +
                "        // broadcast to all subscribers\n" +
                "    }\n" +
                "}\n");

        // ===== repository =====
        javaFileWithImport("repository", "UserRepository",
                "import com.example.model.entity.User;\nimport java.util.List;\nimport java.util.ArrayList;\n",
                "public class UserRepository {\n" +
                "    private final List<User> users = new ArrayList<>();\n" +
                "    public User findById(Long id) {\n" +
                "        return users.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(null);\n" +
                "    }\n" +
                "    public List<User> findAll() { return new ArrayList<>(users); }\n" +
                "    public void save(User user) { users.add(user); }\n" +
                "    public void deleteById(Long id) { users.removeIf(u -> u.getId().equals(id)); }\n" +
                "}\n");

        javaFileWithImport("repository", "OrderRepository",
                "import com.example.model.entity.Order;\nimport java.util.List;\nimport java.util.ArrayList;\n",
                "public class OrderRepository {\n" +
                "    private final List<Order> orders = new ArrayList<>();\n" +
                "    public Order findById(Long id) {\n" +
                "        return orders.stream().filter(o -> o.getId().equals(id)).findFirst().orElse(null);\n" +
                "    }\n" +
                "    public List<Order> findAll() { return new ArrayList<>(orders); }\n" +
                "    public void save(Order order) { orders.add(order); }\n" +
                "    public List<Order> findByUserId(Long userId) {\n" +
                "        List<Order> result = new ArrayList<>();\n" +
                "        for (Order o : orders) { if (o.getUserId().equals(userId)) result.add(o); }\n" +
                "        return result;\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("repository", "ProductRepository",
                "import com.example.model.entity.Product;\nimport java.util.List;\nimport java.util.ArrayList;\n",
                "public class ProductRepository {\n" +
                "    private final List<Product> products = new ArrayList<>();\n" +
                "    public Product findById(Long id) {\n" +
                "        return products.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);\n" +
                "    }\n" +
                "    public List<Product> findAll() { return new ArrayList<>(products); }\n" +
                "    public List<Product> search(String keyword) {\n" +
                "        List<Product> result = new ArrayList<>();\n" +
                "        for (Product p : products) { if (p.getName().contains(keyword)) result.add(p); }\n" +
                "        return result;\n" +
                "    }\n" +
                "    public void save(Product p) { products.add(p); }\n" +
                "}\n");

        javaFileWithImport("repository", "CategoryRepository",
                "import com.example.model.entity.Category;\nimport java.util.List;\nimport java.util.ArrayList;\n",
                "public class CategoryRepository {\n" +
                "    private final List<Category> categories = new ArrayList<>();\n" +
                "    public List<Category> findAll() { return new ArrayList<>(categories); }\n" +
                "    public Category findById(Long id) {\n" +
                "        return categories.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);\n" +
                "    }\n" +
                "    public List<Category> findByParentId(Long parentId) {\n" +
                "        List<Category> result = new ArrayList<>();\n" +
                "        for (Category c : categories) {\n" +
                "            if (parentId == null && c.getParentId() == null) result.add(c);\n" +
                "            else if (parentId != null && parentId.equals(c.getParentId())) result.add(c);\n" +
                "        }\n" +
                "        return result;\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("repository", "BaseRepository",
                "import java.util.List;\n",
                "public interface BaseRepository<T, ID> {\n" +
                "    T findById(ID id);\n" +
                "    List<T> findAll();\n" +
                "    void save(T entity);\n" +
                "    void deleteById(ID id);\n" +
                "}\n");

        javaFile("repository", "GenericRepository",
                "import java.util.*;\n" +
                "public class GenericRepository<T> {\n" +
                "    protected final List<T> store = new ArrayList<>();\n" +
                "    public T findOne(java.util.function.Predicate<T> predicate) {\n" +
                "        return store.stream().filter(predicate).findFirst().orElse(null);\n" +
                "    }\n" +
                "    public List<T> findAll() { return new ArrayList<>(store); }\n" +
                "    public void add(T item) { store.add(item); }\n" +
                "    public void remove(java.util.function.Predicate<T> predicate) {\n" +
                "        store.removeIf(predicate);\n" +
                "    }\n" +
                "    public long count() { return store.size(); }\n" +
                "}\n");

        // ===== config =====
        javaFile("config", "AppConfig",
                "public class AppConfig {\n" +
                "    private String appName = \"MyApp\";\n" +
                "    private String version = \"1.0.0\";\n" +
                "    private int port = 8080;\n" +
                "    private String dbUrl = \"jdbc:mysql://localhost:3306/mydb\";\n" +
                "    public String getAppName() { return appName; }\n" +
                "    public void setAppName(String appName) { this.appName = appName; }\n" +
                "    public String getVersion() { return version; }\n" +
                "    public int getPort() { return port; }\n" +
                "    public String getDbUrl() { return dbUrl; }\n" +
                "}\n");

        javaFile("config", "SecurityConfig",
                "public class SecurityConfig {\n" +
                "    private String secretKey = \"default-secret\";\n" +
                "    private long tokenExpiration = 3600000;\n" +
                "    private boolean enableCors = true;\n" +
                "    public String getSecretKey() { return secretKey; }\n" +
                "    public long getTokenExpiration() { return tokenExpiration; }\n" +
                "    public boolean isEnableCors() { return enableCors; }\n" +
                "}\n");

        javaFile("config", "CacheConfig",
                "public class CacheConfig {\n" +
                "    private int maxSize = 1000;\n" +
                "    private long ttlSeconds = 300;\n" +
                "    private String strategy = \"LRU\";\n" +
                "    public int getMaxSize() { return maxSize; }\n" +
                "    public long getTtlSeconds() { return ttlSeconds; }\n" +
                "    public String getStrategy() { return strategy; }\n" +
                "}\n");

        javaFile("config", "DataSourceConfig",
                "public class DataSourceConfig {\n" +
                "    private String driverClassName = \"com.mysql.cj.jdbc.Driver\";\n" +
                "    private String url;\n" +
                "    private String username;\n" +
                "    private String password;\n" +
                "    private int maxPoolSize = 20;\n" +
                "    public String getDriverClassName() { return driverClassName; }\n" +
                "    public String getUrl() { return url; }\n" +
                "    public void setUrl(String url) { this.url = url; }\n" +
                "    public String getUsername() { return username; }\n" +
                "    public void setUsername(String username) { this.username = username; }\n" +
                "    public String getPassword() { return password; }\n" +
                "    public void setPassword(String password) { this.password = password; }\n" +
                "    public int getMaxPoolSize() { return maxPoolSize; }\n" +
                "}\n");

        // ===== util =====
        javaFile("util", "StringUtils",
                "public class StringUtils {\n" +
                "    public static boolean isEmpty(String s) { return s == null || s.isEmpty(); }\n" +
                "    public static boolean isNotEmpty(String s) { return !isEmpty(s); }\n" +
                "    public static String capitalize(String s) {\n" +
                "        if (isEmpty(s)) return s;\n" +
                "        return s.substring(0, 1).toUpperCase() + s.substring(1);\n" +
                "    }\n" +
                "    public static String truncate(String s, int maxLen) {\n" +
                "        if (s == null || s.length() <= maxLen) return s;\n" +
                "        return s.substring(0, maxLen) + \"...\";\n" +
                "    }\n" +
                "}\n");

        javaFileWithImport("util", "DateUtils",
                "import java.time.LocalDateTime;\nimport java.time.format.DateTimeFormatter;\n",
                "public class DateUtils {\n" +
                "    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm:ss\");\n" +
                "    public static String format(LocalDateTime dt) { return dt != null ? dt.format(FMT) : null; }\n" +
                "    public static LocalDateTime parse(String s) { return LocalDateTime.parse(s, FMT); }\n" +
                "    public static LocalDateTime now() { return LocalDateTime.now(); }\n" +
                "}\n");

        javaFileWithImport("util", "ValidationUtils",
                "import java.util.regex.Pattern;\n",
                "public class ValidationUtils {\n" +
                "    private static final Pattern EMAIL = Pattern.compile(\"^[\\\\w.-]+@[\\\\w.-]+\\\\.[a-zA-Z]{2,}$\");\n" +
                "    public static boolean isValidEmail(String email) {\n" +
                "        return email != null && EMAIL.matcher(email).matches();\n" +
                "    }\n" +
                "    public static boolean isValidPhone(String phone) {\n" +
                "        return phone != null && phone.matches(\"\\\\d{10,11}\");\n" +
                "    }\n" +
                "    public static boolean isPositive(Long n) { return n != null && n > 0; }\n" +
                "}\n");

        javaFileWithImport("util", "JsonUtils",
                "import java.lang.reflect.Field;\n",
                "public class JsonUtils {\n" +
                "    public static String toJson(Object obj) {\n" +
                "        if (obj == null) return \"null\";\n" +
                "        StringBuilder sb = new StringBuilder(\"{\");\n" +
                "        Field[] fields = obj.getClass().getDeclaredFields();\n" +
                "        for (int i = 0; i < fields.length; i++) {\n" +
                "            if (i > 0) sb.append(\",\");\n" +
                "            fields[i].setAccessible(true);\n" +
                "            try {\n" +
                "                sb.append(\"\\\"\").append(fields[i].getName()).append(\"\\\":\");\n" +
                "                sb.append(\"\\\"\").append(fields[i].get(obj)).append(\"\\\"\");\n" +
                "            } catch (Exception e) { sb.append(\"null\"); }\n" +
                "        }\n" +
                "        sb.append(\"}\");\n" +
                "        return sb.toString();\n" +
                "    }\n" +
                "}\n");

        // ===== exception =====
        javaFile("exception", "BusinessException",
                "public class BusinessException extends RuntimeException {\n" +
                "    private int code;\n" +
                "    public BusinessException(int code, String message) { super(message); this.code = code; }\n" +
                "    public int getCode() { return code; }\n" +
                "}\n");

        javaFile("exception", "NotFoundException",
                "public class NotFoundException extends BusinessException {\n" +
                "    public NotFoundException(String resource, Long id) {\n" +
                "        super(404, resource + \" not found: \" + id);\n" +
                "    }\n" +
                "}\n");

        javaFile("exception", "ValidationException",
                "public class ValidationException extends BusinessException {\n" +
                "    public ValidationException(String field, String reason) {\n" +
                "        super(400, \"Validation failed for \" + field + \": \" + reason);\n" +
                "    }\n" +
                "}\n");

        // ===== test 目录 =====
        Path srcTestJava = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(srcTestJava);

        writeTestFile(srcTestJava, "UserServiceTest",
                "import com.example.service.UserService;\n" +
                "public class UserServiceTest {\n" +
                "    private UserService userService;\n" +
                "    public void setUp() { userService = new UserService(); }\n" +
                "    public void testFindById() { /* test */ }\n" +
                "    public void testCreateUser() { /* test */ }\n" +
                "    public void testDeleteUser() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "OrderServiceTest",
                "import com.example.service.OrderService;\n" +
                "public class OrderServiceTest {\n" +
                "    private OrderService orderService;\n" +
                "    public void setUp() { orderService = new OrderService(); }\n" +
                "    public void testCreateOrder() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "ProductServiceTest",
                "import com.example.service.ProductService;\n" +
                "public class ProductServiceTest {\n" +
                "    private ProductService productService;\n" +
                "    public void setUp() { productService = new ProductService(); }\n" +
                "    public void testSearch() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "UserControllerTest",
                "import com.example.controller.UserController;\n" +
                "public class UserControllerTest {\n" +
                "    private UserController controller;\n" +
                "    public void setUp() { controller = new UserController(); }\n" +
                "    public void testGetUser() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "AuthControllerTest",
                "import com.example.controller.AuthController;\n" +
                "public class AuthControllerTest {\n" +
                "    private AuthController controller;\n" +
                "    public void setUp() { controller = new AuthController(); }\n" +
                "    public void testLogin() { /* test */ }\n" +
                "    public void testLogout() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "UserRepositoryTest",
                "import com.example.repository.UserRepository;\n" +
                "public class UserRepositoryTest {\n" +
                "    private UserRepository repo;\n" +
                "    public void setUp() { repo = new UserRepository(); }\n" +
                "    public void testSaveAndFind() { /* test */ }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "StringUtilsTest",
                "import com.example.util.StringUtils;\n" +
                "public class StringUtilsTest {\n" +
                "    public void testIsEmpty() {\n" +
                "        assert StringUtils.isEmpty(null);\n" +
                "        assert StringUtils.isEmpty(\"\");\n" +
                "        assert !StringUtils.isEmpty(\"hi\");\n" +
                "    }\n" +
                "}\n");
        totalJavaFiles++;

        writeTestFile(srcTestJava, "DateUtilsTest",
                "import com.example.util.DateUtils;\n" +
                "public class DateUtilsTest {\n" +
                "    public void testFormat() {\n" +
                "        String s = DateUtils.format(DateUtils.now());\n" +
                "        assert s != null;\n" +
                "    }\n" +
                "}\n");
        totalJavaFiles++;

        // 生成一些非 Java 文件作为噪音
        Files.write(srcMainJava.resolve("application.properties"),
                "server.port=8080\nspring.datasource.url=jdbc:mysql://localhost:3306/test\n".getBytes());
        Files.write(tempDir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n</project>\n".getBytes());
        Files.write(srcMainJava.resolve("test.json"),
                "{\"key\": \"value\"}\n".getBytes());

        System.out.println("生成 " + totalJavaFiles + " 个 Java 文件");
    }

    private void writeTestFile(Path dir, String className, String body) throws IOException {
        Files.write(dir.resolve(className + ".java"),
                ("package com.example;\n\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    // ===== 测试用例 =====

    /**
     * 全量同步：init + 首次 index（相当于 sync 首次运行），
     * 验证所有文件被索引，节点和边数量合理。
     */
    @Test
    public void testFullSync_allFilesIndexed() throws Exception {
        generateProject();

        // init
        int code = new CommandLine(new CodeGraphCli()).execute(
                "init", "-f", "-p", tempDir.toString());
        assertEquals(0, code);

        // 全量同步
        long start = System.nanoTime();
        SyncResult result;
        try (DatabaseConnection db = new DatabaseConnection(
                tempDir.resolve(".codegraph/codegraph4j.db").toString())) {
            db.open();
            QueryBuilder qb = new QueryBuilder(db);
            SyncOrchestrator orch = new SyncOrchestrator();
            result = orch.sync(tempDir.toAbsolutePath(), qb, false, null);
        }
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(totalJavaFiles, result.getFilesAdded());
        assertEquals(0, result.getFilesModified());
        assertEquals(0, result.getFilesRemoved());
        assertEquals(totalJavaFiles, result.getFilesChecked());
        assertTrue("nodesUpdated > 0", result.getNodesUpdated() > 0);

        System.out.println();
        System.out.println("========== 全量同步结果 ==========");
        System.out.println("  Java 文件数: " + totalJavaFiles);
        System.out.println("  filesAdded : " + result.getFilesAdded());
        System.out.println("  nodesUpdated: " + result.getNodesUpdated());
        System.out.println("  durationMs : " + totalMs + "ms");
        System.out.println("===================================");
    }

    /**
     * 幂等同步：全量同步后再次同步，确认无变化。
     */
    @Test
    public void testIdempotentSync_afterFullSync() throws Exception {
        generateProject();

        String dbPath = tempDir.resolve(".codegraph/codegraph4j.db").toString();
        new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", tempDir.toString());

        // 第一次同步
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            SyncOrchestrator orch = new SyncOrchestrator();
            SyncResult r1 = orch.sync(tempDir, new QueryBuilder(db), false, null);
            assertEquals(totalJavaFiles, r1.getFilesAdded());
        }

        // 第二次同步 — 预期无变化
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            SyncOrchestrator orch = new SyncOrchestrator();
            SyncResult r2 = orch.sync(tempDir, new QueryBuilder(db), false, null);

            assertEquals("filesChecked", totalJavaFiles, r2.getFilesChecked());
            assertEquals("filesAdded", 0, r2.getFilesAdded());
            assertEquals("filesModified", 0, r2.getFilesModified());
            assertEquals("filesRemoved", 0, r2.getFilesRemoved());
            assertEquals("filesChanged", 0, r2.getFilesChanged());
            assertTrue("durationMs >= 0", r2.getDurationMs() >= 0);
        }

        System.out.println("幂等同步验证通过：第二次 sync 无变化");
    }

    /**
     * 增量同步：修改若干文件，验证仅修改的文件被重新索引。
     */
    @Test
    public void testIncrementalSync_modifySomeFiles() throws Exception {
        generateProject();

        String dbPath = tempDir.resolve(".codegraph/codegraph4j.db").toString();
        new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", tempDir.toString());

        // 全量同步
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        // 修改 3 个文件
        Thread.sleep(10); // 确保 mtime 变化
        Path ctrlDir = srcMainJava.resolve("controller");
        Path svcDir = srcMainJava.resolve("service");
        String newContent = "package com.example.controller;\n" +
                "public class UserController {\n" +
                "    public String hello() { return \"modified\"; }\n" +
                "}\n";
        Files.write(ctrlDir.resolve("UserController.java"), newContent.getBytes(StandardCharsets.UTF_8));
        Files.write(ctrlDir.resolve("HealthController.java"),
                ("package com.example.controller;\n" +
                 "public class HealthController {\n" +
                 "    public String health() { return \"MODIFIED\"; }\n" +
                 "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(svcDir.resolve("EmailService.java"),
                ("package com.example.service;\n" +
                 "public class EmailService {\n" +
                 "    public void send(String email) { /* modified */ }\n" +
                 "}\n").getBytes(StandardCharsets.UTF_8));

        // 增量同步
        SyncResult result;
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            result = new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        assertEquals(totalJavaFiles, result.getFilesChecked());
        assertEquals(0, result.getFilesAdded());
        assertEquals(3, result.getFilesModified());
        assertEquals(0, result.getFilesRemoved());

        System.out.println();
        System.out.println("========== 增量同步结果 ==========");
        System.out.println("  filesModified: " + result.getFilesModified() + " (预期 3)");
        System.out.println("  changedPaths  : " + result.getChangedFilePaths().size());
        System.out.println("===================================");
    }

    /**
     * 混合变更：新增 + 修改 + 删除。
     */
    @Test
    public void testSync_mixedChangesAddModifyDelete() throws Exception {
        generateProject();

        String dbPath = tempDir.resolve(".codegraph/codegraph4j.db").toString();
        new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", tempDir.toString());

        // 全量同步
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        Thread.sleep(10);

        // 新增 2 个文件
        javaFile("model/dto", "LoginRequest",
                "public class LoginRequest {\n" +
                "    private String username;\n" +
                "    private String password;\n" +
                "    public String getUsername() { return username; }\n" +
                "    public void setUsername(String username) { this.username = username; }\n" +
                "    public String getPassword() { return password; }\n" +
                "    public void setPassword(String password) { this.password = password; }\n" +
                "}\n");
        javaFile("model/dto", "LoginResponse",
                "public class LoginResponse {\n" +
                "    private String token;\n" +
                "    private long expiresIn;\n" +
                "    public String getToken() { return token; }\n" +
                "    public void setToken(String token) { this.token = token; }\n" +
                "    public long getExpiresIn() { return expiresIn; }\n" +
                "    public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }\n" +
                "}\n");

        // 修改 2 个
        Files.write(srcMainJava.resolve("controller/AdminController.java"),
                ("package com.example.controller;\n" +
                 "public class AdminController {\n" +
                 "    public String status() { return \"healthy\"; }\n" +
                 "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(srcMainJava.resolve("config/AppConfig.java"),
                ("package com.example.config;\n" +
                 "public class AppConfig {\n" +
                 "    private String name = \"Modified\";\n" +
                 "    public String getName() { return name; }\n" +
                 "}\n").getBytes(StandardCharsets.UTF_8));

        // 删除 2 个
        Files.delete(srcMainJava.resolve("model/entity/Category.java"));
        Files.delete(srcMainJava.resolve("exception/ValidationException.java"));

        // 同步
        SyncResult result;
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            result = new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        // totalJavaFiles 已经包含了 LoginRequest、LoginResponse
        // 但又删除了 Category、ValidationException
        int expectedChecked = totalJavaFiles - 2; // 删除的 2 个不再存在于文件系统
        assertEquals(expectedChecked, result.getFilesChecked());
        assertEquals(2, result.getFilesAdded());
        assertEquals(2, result.getFilesModified());
        assertEquals(2, result.getFilesRemoved());

        System.out.println();
        System.out.println("========== 混合变更同步结果 ==========");
        System.out.println("  filesChecked : " + result.getFilesChecked());
        System.out.println("  filesAdded   : " + result.getFilesAdded() + " (预期 2)");
        System.out.println("  filesModified: " + result.getFilesModified() + " (预期 2)");
        System.out.println("  filesRemoved : " + result.getFilesRemoved() + " (预期 2)");
        System.out.println("  nodesUpdated : " + result.getNodesUpdated());
        System.out.println("=======================================");
    }

    /**
     * force 强制重新索引所有文件。
     */
    @Test
    public void testForceReindex() throws Exception {
        generateProject();

        String dbPath = tempDir.resolve(".codegraph/codegraph4j.db").toString();
        new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", tempDir.toString());

        // 全量同步
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        // force 重新索引
        SyncResult result;
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            result = new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), true, null);
        }

        assertEquals(totalJavaFiles, result.getFilesChecked());
        assertEquals(0, result.getFilesAdded());
        assertEquals(totalJavaFiles, result.getFilesModified());
        assertEquals(0, result.getFilesRemoved());

        System.out.println();
        System.out.println("========== Force 重索引结果 ==========");
        System.out.println("  filesModified: " + result.getFilesModified() + " (全部重新索引)");
        System.out.println("=========================================");
    }

    /**
     * 排除目录验证：target/ 和 build/ 下的 Java 文件不被索引。
     */
    @Test
    public void testExcludedDirectoriesNotIndexed() throws Exception {
        // 创建正常项目
        generateProject();

        // 在排除目录中创建 Java 文件
        Path targetGen = tempDir.resolve("target/generated-sources");
        Files.createDirectories(targetGen);
        Files.write(targetGen.resolve("Generated.java"),
                "package gen;\npublic class Generated {}\n".getBytes(StandardCharsets.UTF_8));

        Path buildSrc = tempDir.resolve("build/src");
        Files.createDirectories(buildSrc);
        Files.write(buildSrc.resolve("BuildOutput.java"),
                "package build;\npublic class BuildOutput {}\n".getBytes(StandardCharsets.UTF_8));

        String dbPath = tempDir.resolve(".codegraph/codegraph4j.db").toString();
        new CommandLine(new CodeGraphCli()).execute("init", "-f", "-p", tempDir.toString());

        SyncResult result;
        try (DatabaseConnection db = new DatabaseConnection(dbPath)) {
            db.open();
            result = new SyncOrchestrator().sync(tempDir, new QueryBuilder(db), false, null);
        }

        // 排除目录中的文件不计入 filesChecked/filesAdded
        assertEquals(totalJavaFiles, result.getFilesChecked());
        assertEquals(totalJavaFiles, result.getFilesAdded());

        System.out.println();
        System.out.println("排除目录测试通过: target/build 下的文件被正确排除");
    }
}
