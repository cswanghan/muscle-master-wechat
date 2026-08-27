package com.jisuodashi.employment;

import java.util.List;

public interface EmploymentStore {

    void insert(EmploymentModels.Log log);

    List<EmploymentModels.Log> listByStaff(long staffId);

    List<EmploymentModels.Log> listRecent(int limit);
}
