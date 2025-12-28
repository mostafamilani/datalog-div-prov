#pragma once

#include <functional>
#include <set>

namespace dexp {

class rule {
    friend std::hash<rule>;
    friend std::less<dexp::rule>;

public:
    
    rule(int head_id, const std::set<int>& body_ids)
    : m_head_id(head_id), m_body_ids(body_ids) { }

    rule(int head_id, std::set<int>&& body_ids)
    : m_head_id(head_id), m_body_ids(std::move(body_ids)) {}

    void set_global_id(int id) { m_global_id = id; }
    int get_global_id() const { return m_global_id; }

    int get_head_id() const { return m_head_id; }
    const std::set<int>& get_body_ids() const { return m_body_ids; }

    bool operator==(const dexp::rule& second) const {
        return m_head_id == second.m_head_id && m_body_ids == second.m_body_ids;
    }

private:
    int m_global_id{};
    int m_head_id{};
    std::set<int> m_body_ids;
};
}

template<> struct std::hash<dexp::rule> {
    std::size_t operator()(const dexp::rule& r) const noexcept {
        size_t hash = r.m_head_id;
        for(auto& b_fact : r.m_body_ids){
            hash = hash*17 + b_fact;
        }
        return hash;
    }
};

template<> struct std::less<dexp::rule> {
    constexpr bool operator()( const dexp::rule& lhs, const dexp::rule& rhs ) const {
        return (lhs.m_head_id < rhs.m_head_id) || (lhs.m_body_ids < rhs.m_body_ids);
    }
};