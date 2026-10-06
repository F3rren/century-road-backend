package centuryroad.history.repository;

import centuryroad.history.model.ErrorReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorReportRepository extends JpaRepository<ErrorReport, Long> {
}
