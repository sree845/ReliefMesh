
package reliefmesh.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import reliefmesh.util.Dbconnection;

@WebServlet("/db-test")
public class databaseservlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request,
                         HttpServletResponse response)
            throws IOException {

        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");

        try (Connection connection =
                     Dbconnection.getConnection();
             PrintWriter out = response.getWriter()) {

            out.println("ReliefMesh connected to MySQL successfully!");

        } catch (SQLException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().println(
                    "Database connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
