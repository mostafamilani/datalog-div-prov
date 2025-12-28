#pragma once

#include <chrono>

namespace dexp {

template <class DT, class ClockT>
class timer
{
public:
    void tick() { 
        m_end = timep_t{}; 
        m_start = ClockT::now(); 
    }
    
    void tock() { m_end = ClockT::now(); }
    
    auto duration() const { 
        return std::chrono::duration_cast<DT>(m_end - m_start); 
    }

private:
    using timep_t = typename ClockT::time_point;
    timep_t m_start = ClockT::now();
    timep_t m_end = {};
};

}